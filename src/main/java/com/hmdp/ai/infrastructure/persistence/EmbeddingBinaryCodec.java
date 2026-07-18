package com.hmdp.ai.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class EmbeddingBinaryCodec {
    private EmbeddingBinaryCodec() { }
    public static byte[] encode(float[] vector){if(vector==null)return null;ByteBuffer buffer=ByteBuffer.allocate(vector.length*Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);for(float value:vector)buffer.putFloat(value);return buffer.array();}
    public static float[] decode(byte[] bytes){if(bytes==null)return null;ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);float[] result=new float[bytes.length/Float.BYTES];for(int i=0;i<result.length;i++)result[i]=buffer.getFloat();return result;}
}
