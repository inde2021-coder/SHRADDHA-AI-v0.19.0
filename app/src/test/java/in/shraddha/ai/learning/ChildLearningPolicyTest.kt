package `in`.shraddha.ai.learning

import org.junit.Assert.*
import org.junit.Test

class ChildLearningPolicyTest {
    @Test fun mapsOnlyRequestedAgeRange() {
        assertEquals(ChildLearningPolicy.AgeBand.PRESCHOOL_3_TO_5,
            ChildLearningPolicy.Learner(3, true).ageBand())
        assertEquals(ChildLearningPolicy.AgeBand.EARLY_PRIMARY_6_TO_8,
            ChildLearningPolicy.Learner(7, true).ageBand())
        assertEquals(ChildLearningPolicy.AgeBand.PRIMARY_9_TO_10,
            ChildLearningPolicy.Learner(10, true).ageBand())
        assertNull(ChildLearningPolicy.Learner(2, true).ageBand())
        assertNull(ChildLearningPolicy.Learner(11, true).ageBand())
    }

    @Test fun progressesFromQuestionToScaffoldedTeaching() {
        val learner = ChildLearningPolicy.Learner(7, true)
        fun step(attempts: Int) = ChildLearningPolicy.next(
            ChildLearningPolicy.Turn(learner, attempts, null)
        )!!.nextStep
        assertEquals(ChildLearningPolicy.Step.ASK, step(0))
        assertEquals(ChildLearningPolicy.Step.HINT, step(1))
        assertEquals(ChildLearningPolicy.Step.SIMPLE_EXPLANATION, step(2))
        assertEquals(ChildLearningPolicy.Step.WORKED_EXAMPLE, step(3))
        assertEquals(ChildLearningPolicy.Step.RETRY, step(4))
    }

    @Test fun correctAnswerGetsEncouragementAndReview() {
        val result = ChildLearningPolicy.next(
            ChildLearningPolicy.Turn(ChildLearningPolicy.Learner(9, true), 1, true)
        )
        assertEquals(ChildLearningPolicy.Step.ENCOURAGE_AND_REVIEW, result!!.nextStep)
    }

    @Test fun guardianModeAndValidAgeAreRequired() {
        assertNull(ChildLearningPolicy.next(
            ChildLearningPolicy.Turn(ChildLearningPolicy.Learner(5, false), 0, null)
        ))
        assertNull(ChildLearningPolicy.next(
            ChildLearningPolicy.Turn(ChildLearningPolicy.Learner(12, true), 0, null)
        ))
    }

    @Test fun repeatedDifficultySuggestsAdultSupport() {
        val result = ChildLearningPolicy.next(
            ChildLearningPolicy.Turn(ChildLearningPolicy.Learner(6, true), 4, false)
        )
        assertTrue(result!!.suggestAdultSupport)
    }
}
