package `in`.shraddha.ai.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkillDomainCatalogTest {
    @Test fun registryHasTwentyUniqueStableDomains() {
        assertEquals(20, SkillDomainCatalog.domains.size)
        assertEquals(20, SkillDomainCatalog.domains.map { it.id }.toSet().size)
    }
    @Test fun contentSourceStatusDoesNotClaimCatalogOnlyDomainsAreComplete() {
        assertEquals(12, SkillDomainCatalog.domains.count { it.state == SkillDomainCatalog.ContentState.CATALOG_ONLY })
        assertEquals(SkillDomainCatalog.ContentState.CATALOG_ONLY, SkillDomainCatalog.find("technology")?.state)
        assertEquals(SkillDomainCatalog.ContentState.EXISTING_SOURCE_REVIEW_REQUIRED, SkillDomainCatalog.find("child-learning")?.state)
        assertNotNull(SkillDomainCatalog.find("child-learning"))
        assertNull(SkillDomainCatalog.find("unknown"))
    }
}
