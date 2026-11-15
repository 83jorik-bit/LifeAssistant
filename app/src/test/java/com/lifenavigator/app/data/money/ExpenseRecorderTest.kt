package com.lifenavigator.app.data.money

import com.lifenavigator.app.data.model.NeedCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpenseRecorderTest {

    @Test
    fun recognizesGroceryMerchants() {
        assertEquals(NeedCategory.GROCERY.name,
            ExpenseRecorder.categoryKeyFor("Пятерочка"))
    }

    @Test
    fun recognizesPharmacies() {
        assertEquals(NeedCategory.PHARMACY.name,
            ExpenseRecorder.categoryKeyFor("Аптека"))
    }

    @Test
    fun recognizesCafes() {
        assertEquals(NeedCategory.CAFE.name,
            ExpenseRecorder.categoryKeyFor("Кофейня"))
    }

    @Test
    fun recognizesPickupPoints() {
        assertEquals(NeedCategory.PICKUP_POINT.name,
            ExpenseRecorder.categoryKeyFor("Озон"))
    }

    @Test
    fun unknownMerchantIsUncategorized() {
        assertEquals(ExpenseRecorder.UNCATEGORIZED_KEY,
            ExpenseRecorder.categoryKeyFor("Неизвестная организация"))
    }

    @Test
    fun nullMerchantIsUncategorized() {
        assertEquals(ExpenseRecorder.UNCATEGORIZED_KEY,
            ExpenseRecorder.categoryKeyFor(null))
    }
}
