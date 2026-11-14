package io.pocketledger.ledger.application

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class StatementParsersTest {
    @Test
    fun `CSV français avec point-virgule, virgule décimale et colonnes débit-crédit`() {
        val csv =
            """
            Date;Libellé;Débit;Crédit
            05/11/2026;"CARTE BOULANGERIE; PAUL";12,50;
            06/11/2026;VIREMENT SALAIRE;;2 450,00
            bad;ligne cassée;1;
            """.trimIndent()
        val result =
            CsvStatementParser.parse(
                csv,
                CsvMapping(
                    date = "date", amount = null, debit = "débit", credit = "crédit", label = "libellé",
                    dateFormat = "dd/MM/yyyy", delimiter = ';', decimalSeparator = ',',
                ),
            )
        result.rows shouldHaveSize 2
        result.rows[0].amount shouldBe "-12.50"
        result.rows[0].label shouldBe "CARTE BOULANGERIE; PAUL"
        result.rows[1].amount shouldBe "2450.00"
        result.rows[1].date shouldBe LocalDate.of(2026, 11, 6)
        result.errors shouldHaveSize 1
    }

    @Test
    fun `OFX SGML sans balises fermantes`() {
        val ofx =
            """
            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261105120000<TRNAMT>-42.10<FITID>1<NAME>SUPERMARCHE<MEMO>CB 1234
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261106<TRNAMT>1500.00<FITID>2<NAME>SALAIRE
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
            """.trimIndent()
        val result = OfxStatementParser.parse(ofx)
        result.rows shouldHaveSize 2
        result.rows[0].amount shouldBe "-42.10"
        result.rows[0].label shouldBe "SUPERMARCHE — CB 1234"
        result.rows[1].date shouldBe LocalDate.of(2026, 11, 6)
    }

    @Test
    fun `l'empreinte ignore casse, accents et zéros non significatifs`() {
        val account = UUID.randomUUID()
        val a = StatementRow(1, LocalDate.of(2026, 11, 5), "-12.50", "Café  Gare", null)
        val b = StatementRow(7, LocalDate.of(2026, 11, 5), "-12.5", "CAFE GARE", null)
        ImportService.fingerprint(account, a) shouldBe ImportService.fingerprint(account, b)
        ImportService.fingerprint(account, a) shouldNotBe ImportService.fingerprint(UUID.randomUUID(), a)
    }
}
