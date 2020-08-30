import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DummyTest {
    @Test
    fun alwaysTrue() {
        assertTrue(true)
    }
}
