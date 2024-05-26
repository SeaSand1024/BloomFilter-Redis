package com.cyy.core;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.BigInteger;

import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Bytes;

/**
 * @Author chenchen
 * @Date 2021/12/8 5:10 下午
 **/
public class RCustomBloomFilter {
    // expectedInsertions * fpp = 1.0
    static final int expectedInsertions = 1000;//要插入多少数据
    static final double fpp = 0.001;//期望的误判率

    //bit数组长度
    private static long numBits;

    //hash函数数量
    private static int numHashFunctions;

    static {
        numBits = optimalNumOfBits(expectedInsertions, fpp);
        numHashFunctions = optimalNumOfHashFunctions(expectedInsertions, numBits);
    }

    /**
     * 根据key获取bitmap下标
     */
    public static long[] getIndexs(String key) {
        //进行一次哈希
        BigInteger hash1 = hash(key);
        //和long最大值进行一次与运算，生成低位
        BigInteger lower_hash = hash1.and(BigInteger.valueOf(Long.MAX_VALUE));
        //带符号右移，生成高位
        BigInteger height_hash = hash1.shiftRight(64);
        BigInteger combined_hash = lower_hash;
        long[] result = new long[numHashFunctions];
        for (int i = 0; i < numHashFunctions; i++) {
            result[i] = (combined_hash.and(BigInteger.valueOf(Long.MAX_VALUE))).mod(BigInteger.valueOf(numBits)).longValue();
            //与高位做一次与运算
            combined_hash = combined_hash.add(height_hash);
        }
        return result;
    }

    private static BigInteger hash(String key) {
        byte[] mm3_le = Hashing.murmur3_128().hashString(key, UTF_8).asBytes();
        byte[] mm3_be = Bytes.toArray(Lists.reverse(Bytes.asList(mm3_le)));
        return new BigInteger(1, mm3_be);
    }

    //计算hash函数个数
    private static int optimalNumOfHashFunctions(long n, long m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    //计算bit数组长度
    private static long optimalNumOfBits(long n, double p) {
        if (p == 0) {
            p = Double.MIN_VALUE;
        }
        return (long) (-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }
}


