/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.formats.inlongmsg;

import org.apache.inlong.sort.formats.inlongmsg.InLongMsgDeserializationSchema.MetadataConverter;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.format.DecodingFormat;
import org.apache.flink.table.connector.source.DynamicTableSource.Context;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.utils.DataTypeUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InLongMsgDecodingFormat implements DecodingFormat<DeserializationSchema<RowData>> {

    private final String innerFormatMetaPrefix;

    private final DecodingFormat<DeserializationSchema<RowData>> innerDecodingFormat;

    private List<String> metadataKeys;

    private final boolean ignoreErrors;

    public InLongMsgDecodingFormat(
            DecodingFormat<DeserializationSchema<RowData>> innerDecodingFormat,
            String innerFormatMetaPrefix,
            boolean ignoreErrors) {
        this.innerDecodingFormat = innerDecodingFormat;
        this.innerFormatMetaPrefix = innerFormatMetaPrefix;
        this.metadataKeys = Collections.emptyList();
        this.ignoreErrors = ignoreErrors;
    }

    @Override
    public DeserializationSchema<RowData> createRuntimeDecoder(Context context, DataType physicalDataType) {
        final MetadataConverter[] metadataConverters = Arrays.stream(ReadableMetadata.values())
                .filter(metadata -> metadataKeys.contains(metadata.key))
                .map(metadata -> metadata.converter)
                .toArray(MetadataConverter[]::new);
        final List<ReadableMetadata> readableMetadata =
                metadataKeys.stream()
                        .map(
                                k -> Stream.of(ReadableMetadata.values())
                                        .filter(rm -> rm.key.equals(k))
                                        .findFirst()
                                        .orElseThrow(IllegalStateException::new))
                        .collect(Collectors.toList());
        final List<DataTypes.Field> metadataFields =
                readableMetadata.stream()
                        .map(m -> DataTypes.FIELD(m.key, m.dataType))
                        .collect(Collectors.toList());
        final DataType producedDataType =
                DataTypeUtils.appendRowFields(physicalDataType, metadataFields);
        final TypeInformation<RowData> producedTypeInfo =
                context.createTypeInformation(producedDataType);

        return new InLongMsgDeserializationSchema(
                innerDecodingFormat.createRuntimeDecoder(context, physicalDataType),
                metadataConverters,
                producedTypeInfo,
                ignoreErrors);
    }

    @Override
    public Map<String, DataType> listReadableMetadata() {
        final Map<String, DataType> metadataMap = new LinkedHashMap<>();

        // add inner format metadata with prefix
        innerDecodingFormat
                .listReadableMetadata()
                .forEach((key, value) -> metadataMap.putIfAbsent(innerFormatMetaPrefix + key, value));

        // add format metadata
        Stream.of(ReadableMetadata.values())
                .forEachOrdered(m -> metadataMap.putIfAbsent(m.key, m.dataType));

        return metadataMap;
    }

    @Override
    public void applyReadableMetadata(List<String> metadataKeys) {
        // separate inner format and format metadata
        final List<String> innerFormatMetadataKeys =
                metadataKeys.stream()
                        .filter(k -> k.startsWith(innerFormatMetaPrefix))
                        .collect(Collectors.toList());
        final List<String> formatMetadataKeys = new ArrayList<>(metadataKeys);
        formatMetadataKeys.removeAll(innerFormatMetadataKeys);
        this.metadataKeys = formatMetadataKeys;

        // push down inner format metadata
        final Map<String, DataType> formatMetadata = innerDecodingFormat.listReadableMetadata();
        if (formatMetadata.size() > 0) {
            final List<String> requestedFormatMetadataKeys =
                    innerFormatMetadataKeys.stream()
                            .map(k -> k.substring(innerFormatMetaPrefix.length()))
                            .collect(Collectors.toList());
            innerDecodingFormat.applyReadableMetadata(requestedFormatMetadataKeys);
        }
    }

    @Override
    public ChangelogMode getChangelogMode() {
        return innerDecodingFormat.getChangelogMode();
    }

    // --------------------------------------------------------------------------------------------
    // Metadata handling
    // --------------------------------------------------------------------------------------------

    enum ReadableMetadata {

        CREATE_TIME(
                "create-time",
                DataTypes.TIMESTAMP_WITH_LOCAL_TIME_ZONE().notNull(),
                new MetadataConverter() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Object read(InLongMsgHead head) {
                        return TimestampData.fromTimestamp(head.getTime());
                    }
                }),

        STREAM_ID(
                "stream-id",
                DataTypes.STRING().notNull(),
                new MetadataConverter() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Object read(InLongMsgHead head) {
                        return StringData.fromString(head.getTid());
                    }
                });

        final String key;

        final DataType dataType;

        final MetadataConverter converter;

        ReadableMetadata(String key, DataType dataType, MetadataConverter converter) {
            this.key = key;
            this.dataType = dataType;
            this.converter = converter;
        }
    }
}
