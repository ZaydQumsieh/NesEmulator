package model;

// Class Util:
//     A collection of various utility methods.

public final class Util {
    // EFFECTS: makes the code coverage autobot not complain about me not instantiating a Util class.
    public Util() {

    }

    // REQUIRES: value <= 2^n
    // EFFECTS: returns the nth bit of the binary representation of value.
    public static int getNthBit(int value, int n) {
        return (value >> n) & 1;
    }

    // EFFECTS: returns the sign of the given integer interpreted as a signed binary value.
    //          0 if zero, 1 if positive, -1 if negative.
    public static int getSign(int value) {
        if (value == 0) {
            return 0;
        } else {
            return (value <= 127) ? 1 : -1;
        }
    }

    // REQUIRES: value >= 2^(n+len)
    // EFFECTS: returns the bits from value[n] to value[n + len]
    public static int getNthBits(int value, int n, int len) {
        return (value >> n) & (int) (Math.pow(2, len) - 1);
    }

    // EFFECTS: masks the bits from value[valueOffset] to value[valueOffset + len] to the values from
    //          fullMask[maskOffset] to fullMask[maskOffset + len]
    public static int maskNthBits(int fullMask, int value, int maskOffset, int valueOffset, int len) {
        for (int i = 0; i < len; i++) {
            int maskBit = getNthBit(fullMask, maskOffset + i);
            if (maskBit == 0) {
                value &= ~(1 << valueOffset + i);
            } else {
                value |= 1 << (valueOffset + i);
            }
        }

        return value;
    }

    // EFFECTS: reverses the bits[0:numBits] in value and returns the reversal
    public static int reverse(int value, int numBits) {
        int newValue = 0;
        for (int i = 0; i < numBits; i++) {
            newValue += Util.getNthBit(value, i) * Math.pow(2, numBits - i - 1);
        }

        return newValue;
    }

    public static int wrapInt(int value, int lowerBound, int upperBound) {
        while (value > upperBound) {
            value -= upperBound + lowerBound + 1;
        }

        while (value < lowerBound) {
            value += upperBound + lowerBound + 1;
        }

        return value;
    }
}
