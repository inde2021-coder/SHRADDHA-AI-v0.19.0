package com.Lia.assistant.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolverTest {
    @Test fun exactUniqueNameResolves() {
        val result = ContactResolver.resolveCandidates("Ravi", listOf("Ravi" to "+911111111111", "Ravinder" to "+922222222222"))
        assertTrue(result is ContactLookupResult.Found)
        assertEquals("+911111111111", (result as ContactLookupResult.Found).phoneNumber)
    }

    @Test fun duplicateExactNamesAreAmbiguous() {
        val result = ContactResolver.resolveCandidates("Ravi", listOf("Ravi" to "+911111111111", "Ravi" to "+922222222222"))
        assertTrue(result is ContactLookupResult.Ambiguous)
        assertEquals(2, (result as ContactLookupResult.Ambiguous).candidates.size)
    }

    @Test fun uniquePrefixResolvesButMultiplePrefixesAreAmbiguous() {
        val unique = ContactResolver.resolveCandidates("Rav", listOf("Ravi" to "+911111111111"))
        assertTrue(unique is ContactLookupResult.Found)
        val multiple = ContactResolver.resolveCandidates("Rav", listOf("Ravi" to "+911111111111", "Ravinder" to "+922222222222"))
        assertTrue(multiple is ContactLookupResult.Ambiguous)
    }

    @Test fun oneContainsMatchResolvesAndMultipleContainsMatchesDoNotGuess() {
        val unique = ContactResolver.resolveCandidates("avi", listOf("Ravi Kumar" to "+911111111111"))
        assertTrue(unique is ContactLookupResult.Found)
        val multiple = ContactResolver.resolveCandidates("avi", listOf("Ravi Kumar" to "+911111111111", "Aviral" to "+922222222222"))
        assertTrue(multiple is ContactLookupResult.Ambiguous)
    }

    @Test fun missingAndDirectPhoneQueriesAreHandledExplicitly() {
        assertTrue(ContactResolver.resolveCandidates("Unknown", emptyList()) is ContactLookupResult.NotFound)
        assertTrue(ContactResolver.isDirectPhoneNumber("+91 98765-43210"))
        assertTrue(!ContactResolver.isDirectPhoneNumber("Ravi"))
    }
}
