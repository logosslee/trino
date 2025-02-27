/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.raptor.legacy.storage;

import io.airlift.slice.Slice;
import io.trino.spi.Page;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.Int128;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RealType;
import io.trino.spi.type.SmallintType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TinyintType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import org.apache.hadoop.hive.common.type.HiveDecimal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static io.airlift.slice.SizeOf.SIZE_OF_BYTE;
import static io.airlift.slice.SizeOf.SIZE_OF_DOUBLE;
import static io.airlift.slice.SizeOf.SIZE_OF_LONG;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static java.util.Objects.requireNonNull;

public class Row
{
    private final List<Object> columns;
    private final long sizeInBytes;

    public Row(List<Object> columns, long sizeInBytes)
    {
        this.columns = requireNonNull(columns, "columns is null");
        checkArgument(sizeInBytes >= 0, "sizeInBytes must be >= 0");
        this.sizeInBytes = sizeInBytes;
    }

    public List<Object> getColumns()
    {
        return columns;
    }

    public long getSizeInBytes()
    {
        return sizeInBytes;
    }

    public static Row extractRow(Page page, int position, List<Type> types)
    {
        checkArgument(page.getChannelCount() == types.size(), "channelCount does not match");
        checkArgument(position < page.getPositionCount(), "Requested position %s from a page with positionCount %s ", position, page.getPositionCount());

        RowBuilder rowBuilder = new RowBuilder(page.getChannelCount());
        for (int channel = 0; channel < page.getChannelCount(); channel++) {
            Block block = page.getBlock(channel);
            Type type = types.get(channel);
            long size;
            Object value = getNativeContainerValue(type, block, position);
            if (value == null) {
                size = SIZE_OF_BYTE;
            }
            else if (type.getJavaType() == boolean.class) {
                size = SIZE_OF_BYTE;
            }
            else if (type.getJavaType() == long.class) {
                size = SIZE_OF_LONG;
            }
            else if (type.getJavaType() == double.class) {
                size = SIZE_OF_DOUBLE;
            }
            else if (type.getJavaType() == Slice.class) {
                size = ((Slice) value).length();
            }
            else if (type.getJavaType() == Block.class) {
                size = ((Block) value).getSizeInBytes();
            }
            else if (type.getJavaType() == Int128.class) {
                size = Int128.SIZE;
            }
            else {
                throw new AssertionError("Unimplemented type: " + type);
            }
            rowBuilder.add(nativeContainerToOrcValue(type, value), size);
        }
        Row row = rowBuilder.build();
        verify(row.getColumns().size() == types.size(), "Column count in row: %s Expected column count: %s", row.getColumns().size(), types.size());
        return row;
    }

    private static Object getNativeContainerValue(Type type, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }
        else if (type.getJavaType() == boolean.class) {
            return type.getBoolean(block, position);
        }
        else if (type.getJavaType() == long.class) {
            return type.getLong(block, position);
        }
        else if (type.getJavaType() == double.class) {
            return type.getDouble(block, position);
        }
        else if (type.getJavaType() == Slice.class) {
            return type.getSlice(block, position);
        }
        else if (type.getJavaType() == Block.class) {
            return type.getObject(block, position);
        }
        else if (type.getJavaType() == Int128.class) {
            return type.getObject(block, position);
        }
        else {
            throw new AssertionError("Unimplemented type: " + type);
        }
    }

    private static Object nativeContainerToOrcValue(Type type, Object nativeValue)
    {
        if (nativeValue == null) {
            return null;
        }
        if (type instanceof DecimalType) {
            BigInteger unscaledValue;
            DecimalType decimalType = (DecimalType) type;
            if (decimalType.isShort()) {
                unscaledValue = BigInteger.valueOf((long) nativeValue);
            }
            else {
                unscaledValue = ((Int128) nativeValue).toBigInteger();
            }
            return HiveDecimal.create(unscaledValue, decimalType.getScale());
        }
        if ((type == BooleanType.BOOLEAN) ||
                (type == SmallintType.SMALLINT) ||
                (type == IntegerType.INTEGER) ||
                (type == BigintType.BIGINT) ||
                (type == DoubleType.DOUBLE) ||
                (type == DateType.DATE) ||
                type.equals(TimestampType.TIMESTAMP_MILLIS)) {
            return nativeValue;
        }
        if (type == TinyintType.TINYINT) {
            return ((Number) nativeValue).byteValue();
        }
        if (type == RealType.REAL) {
            return Float.intBitsToFloat(((Number) nativeValue).intValue());
        }
        if (type instanceof VarcharType) {
            return ((Slice) nativeValue).toStringUtf8();
        }
        if (type == VarbinaryType.VARBINARY) {
            return ((Slice) nativeValue).getBytes();
        }
        if (type instanceof ArrayType) {
            Block arrayBlock = (Block) nativeValue;
            Type elementType = type.getTypeParameters().get(0);
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < arrayBlock.getPositionCount(); i++) {
                list.add(nativeContainerToOrcValue(elementType, getNativeContainerValue(elementType, arrayBlock, i)));
            }
            return list;
        }
        if (type instanceof MapType) {
            Block mapBlock = (Block) nativeValue;
            Type keyType = type.getTypeParameters().get(0);
            Type valueType = type.getTypeParameters().get(1);
            Map<Object, Object> map = new HashMap<>();
            for (int i = 0; i < mapBlock.getPositionCount(); i += 2) {
                Object key = nativeContainerToOrcValue(keyType, getNativeContainerValue(keyType, mapBlock, i));
                Object value = nativeContainerToOrcValue(valueType, getNativeContainerValue(valueType, mapBlock, i + 1));
                map.put(key, value);
            }
            return map;
        }
        throw new TrinoException(GENERIC_INTERNAL_ERROR, "Unimplemented type: " + type);
    }

    private static class RowBuilder
    {
        private long rowSize;
        private final List<Object> columns;

        public RowBuilder(int columnCount)
        {
            this.columns = new ArrayList<>(columnCount);
        }

        public void add(Object value, long size)
        {
            columns.add(value);
            rowSize += size;
        }

        public Row build()
        {
            return new Row(columns, rowSize);
        }
    }
}
