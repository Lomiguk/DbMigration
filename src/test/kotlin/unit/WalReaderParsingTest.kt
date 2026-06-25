package unit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import replication.WalReader
import java.util.UUID
import javax.sql.DataSource
import io.mockk.mockk

class WalReaderParsingTest {

    @Test
    fun `parseValue should convert primitive postgres text values`() {
        val reader = WalReader(mockk<DataSource>())

        assertThat(parseValue(reader, "42")).isEqualTo(42L)
        assertThat(parseValue(reader, "-3.5")).isEqualTo(-3.5)
        assertThat(parseValue(reader, "t")).isEqualTo(true)
        assertThat(parseValue(reader, "f")).isEqualTo(false)
        assertThat(parseValue(reader, "null")).isNull()
        assertThat(parseValue(reader, "pending")).isEqualTo("pending")
    }

    @Test
    fun `parseValue should convert uuid values with or without quotes`() {
        val reader = WalReader(mockk<DataSource>())
        val uuid = UUID.randomUUID()

        assertThat(parseValue(reader, uuid.toString())).isEqualTo(uuid)
        assertThat(parseValue(reader, "'$uuid'")).isEqualTo(uuid)
        assertThat(parseValue(reader, "'plain text'")).isEqualTo("plain text")
    }

    private fun parseValue(reader: WalReader, value: String): Any? {
        val method = WalReader::class.java.getDeclaredMethod("parseValue", String::class.java)
        method.isAccessible = true
        return method.invoke(reader, value)
    }
}
