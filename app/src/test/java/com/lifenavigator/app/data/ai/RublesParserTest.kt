package com.lifenavigator.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RublesParserTest {

    @Test
    fun parsesThousands() {
        assertEquals(2000, RublesParser.parse("добавь расход 2 тысячи рублей"))
    }

    @Test
    fun parsesThousandsWithExtraHundreds() {
        assertEquals(2500, RublesParser.parse("2 тысячи 500 рублей"))
    }

    @Test
    fun parsesRussianNumberWords() {
        assertEquals(2000, RublesParser.parse("две тысячи рублей"))
    }

    @Test
    fun parsesRussianWordsWithNumericRemainder() {
        assertEquals(2500, RublesParser.parse("две тысячи 500 рублей"))
    }

    @Test
    fun parsesGroupedNumbers() {
        assertEquals(2500, RublesParser.parse("2 500 рублей"))
        assertEquals(2500, RublesParser.parse("2\u00A0500 рублей"))
    }

    @Test
    fun parsesLargeGroupedNumbers() {
        assertEquals(1250000, RublesParser.parse("1 250 000 рублей"))
    }

    @Test
    fun parsesOrdinaryAmounts() {
        assertEquals(350, RublesParser.parse("350 рублей"))
    }

    @Test
    fun rejectsMissingAmount() {
        assertNull(RublesParser.parse("без суммы"))
    }

    @Test
    fun rejectsIntegerOverflow() {
        assertNull(RublesParser.parse("2147484 тысячи рублей"))
        assertNull(RublesParser.parse("999999999999 рублей"))
    }
}
