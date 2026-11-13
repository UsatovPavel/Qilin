package qilin.ucfs.benchmark

import org.junit.Test
import qilin.ucfs.main
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliValidationTest {
    @Test
    fun archivedSubsetIsRejectedBeforeQilinSetup() {
        val error = assertFailsWith<IllegalArgumentException> {
            main(arrayOf("--solver=typed-subset", "--"))
        }
        assertTrue(error.message.orEmpty().contains("archived"))
        assertTrue(error.message.orEmpty().contains("--solver=typed"))
    }
}
