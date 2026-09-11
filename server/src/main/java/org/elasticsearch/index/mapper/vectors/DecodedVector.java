/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper.vectors;

import org.elasticsearch.index.codec.vectors.BFloat16;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.ElementType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * A dense vector decoded from a hex or base64 string.
 */
public final class DecodedVector {

    /**
     * How the decoded bytes should be read as vector components.
     */
    private enum Layout {
        BYTES,
        FLOAT32,
        BFLOAT16
    }

    private final byte[] bytes;
    private final Layout layout;

    private DecodedVector(byte[] bytes, Layout layout) {
        this.bytes = bytes;
        this.layout = layout;
    }

    /**
     * Decodes a dense vector supplied as a hex or base64 string, resolving which encoding was used and how
     * the resulting bytes should be read.
     *
     * @param encoded     hex or base64 string
     * @param elementType element type of the field
     * @param dims        expected number of dimensions
     * @throws IllegalArgumentException if the string cannot be decoded or doesn't match the expected dimensions
     */
    public static DecodedVector decode(String encoded, ElementType elementType, int dims) {
        byte[] hexBytes = tryParseHex(encoded);

        // Prefer hex if it matches expected dimensions (hex always produces byte[])
        if (hexBytes != null && hexBytes.length == dims) {
            return new DecodedVector(hexBytes, Layout.BYTES);
        }

        // For BIT element type, check hex with bit dimensions
        if (elementType == ElementType.BIT && hexBytes != null && hexBytes.length == dims / Byte.SIZE) {
            return new DecodedVector(hexBytes, Layout.BYTES);
        }

        byte[] base64Bytes = tryParseBase64(encoded);

        if (hexBytes == null && base64Bytes == null) {
            throw new IllegalArgumentException("failed to decode vector: value must be a valid base64 or hex string");
        }

        // Try base64 if it matches expected dimensions for the element type
        if (base64Bytes != null && matchesExpectedBase64Length(base64Bytes.length, elementType, dims)) {
            return new DecodedVector(base64Bytes, layoutFor(elementType, base64Bytes.length, dims));
        }

        // Hex decoded cleanly but doesn't match the expected dimensions
        if (hexBytes != null) {
            throw new IllegalArgumentException(
                "failed to decode vector: hex-decoded vector has a different number of dimensions ["
                    + hexBytes.length
                    + "] than the expected ["
                    + dims
                    + "]"
            );
        }

        // base64 was parsed but doesn't match dimensions
        throw invalidBase64Length(base64Bytes.length, elementType);
    }

    public boolean isByteVector() {
        return layout == Layout.BYTES;
    }

    public byte[] bytes() {
        if (isByteVector() == false) {
            throw new IllegalStateException("vector components are not bytes, layout is [" + layout + "]");
        }
        return bytes;
    }

    public float[] toFloatArray() {
        float[] values = new float[componentCount()];
        switch (layout) {
            case BYTES -> {
                for (int i = 0; i < values.length; i++) {
                    values[i] = bytes[i];
                }
            }
            case FLOAT32 -> ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(values);
            case BFLOAT16 -> BFloat16.bFloat16ToFloat(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN), values);
        }
        return values;
    }

    public List<Object> toFloatList() {
        List<Object> values = new ArrayList<>(componentCount());
        switch (layout) {
            case BYTES -> {
                for (byte b : bytes) {
                    values.add((float) b);
                }
            }
            case FLOAT32 -> {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
                int count = bytes.length / Float.BYTES;
                for (int i = 0; i < count; i++) {
                    values.add(buffer.getFloat());
                }
            }
            case BFLOAT16 -> {
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
                int count = bytes.length / BFloat16.BYTES;
                for (int i = 0; i < count; i++) {
                    values.add(BFloat16.bFloat16ToFloat(buffer.getShort()));
                }
            }
        }
        return values;
    }

    /**
     * Returns the canonical base64 encoding of this vector. For byte and float32 vectors, the raw bytes are encoded
     * directly. For bfloat16 vectors, each component is widened to a 4-byte big-endian float before encoding,
     * so the result is always 4 bytes per component regardless of the original storage format.
     */
    public String toBase64() {
        return switch (layout) {
            case BYTES, FLOAT32 -> Base64.getEncoder().encodeToString(bytes);
            case BFLOAT16 -> {
                ByteBuffer buffer = ByteBuffer.allocate(componentCount() * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
                buffer.asFloatBuffer().put(toFloatArray());
                yield Base64.getEncoder().encodeToString(buffer.array());
            }
        };
    }

    private int componentCount() {
        return switch (layout) {
            case BYTES -> bytes.length;
            case FLOAT32 -> bytes.length / Float.BYTES;
            case BFLOAT16 -> bytes.length / BFloat16.BYTES;
        };
    }

    private static Layout layoutFor(ElementType elementType, int length, int dims) {
        return switch (elementType) {
            case BYTE, BIT -> Layout.BYTES;
            case FLOAT -> Layout.FLOAT32;
            // Prefer bfloat16 if it matches exactly, otherwise float
            case BFLOAT16 -> length == dims * BFloat16.BYTES ? Layout.BFLOAT16 : Layout.FLOAT32;
        };
    }

    private static byte[] tryParseHex(String encoded) {
        try {
            return HexFormat.of().parseHex(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] tryParseBase64(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean matchesExpectedBase64Length(int length, ElementType elementType, int dims) {
        return switch (elementType) {
            case BYTE -> length == dims;
            case BIT -> length == dims / Byte.SIZE;
            case FLOAT -> length == dims * Float.BYTES;
            case BFLOAT16 -> length == dims * Float.BYTES || length == dims * BFloat16.BYTES;
        };
    }

    private static IllegalArgumentException invalidBase64Length(int length, ElementType elementType) {
        String expectedType = switch (elementType) {
            case BYTE, BIT -> "byte";
            case FLOAT -> "float";
            case BFLOAT16 -> "float or bfloat16";
        };
        return new IllegalArgumentException(
            "failed to decode vector: value must contain a valid Base64-encoded "
                + expectedType
                + " vector, but the decoded bytes length ["
                + length
                + "] is not compatible with the expected vector length"
        );
    }
}
