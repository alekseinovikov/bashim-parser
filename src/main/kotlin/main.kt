import org.apache.commons.codec.digest.DigestUtils
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

val pageCounter = AtomicInteger(0)
val quotesCounter = AtomicInteger(0)

fun main(args: Array<String>) {
    initDatabase()
    processPagesUntilTheEnd()
}

private fun processPagesUntilTheEnd() {
    var currentIndex = 3357
    var currentPage = getPageByIndex(currentIndex)
    while (isPageOnCurrentIndex(currentPage, currentIndex++)) {
        extractAndSaveQuotes(currentPage)
        currentPage = getPageByIndex(currentIndex)
    }
}

private fun extractAndSaveQuotes(doc: Document) =
    saveOrUpdate(doc.findAllQuotes())

private fun initDatabase() {
    Database.connect(
        "jdbc:postgresql://localhost:5432/bashim", driver = "org.postgresql.Driver",
        user = "root", password = "123456"
    )

    transaction {
        SchemaUtils.create(Quotes)
    }
}

private fun saveOrUpdate(quotes: List<Quote>) {
    transaction {
        quotes.forEach { quote ->
            when (Quotes.select { Quotes.md5Hash eq quote.md5Hash }.singleOrNull()) {
                null -> save(quote)
                else -> update(quote)
            }
        }
    }

    val pagesProcessed = pageCounter.addAndGet(1)
    val quotesProcessed = quotesCounter.addAndGet(quotes.size)

    println("Pages processed: $pagesProcessed Quotes processed: $quotesProcessed")
}

private fun save(quote: Quote) {
    Quotes.insert {
        it[text] = quote.text
        it[dateTime] = quote.quoteDateTime
        it[votes] = quote.votes
        it[md5Hash] = quote.md5Hash
    }
}

private fun update(quote: Quote) {
    Quotes.update({ Quotes.md5Hash eq quote.md5Hash }) {
        it[text] = quote.text
        it[votes] = quote.votes
        it[dateTime] = quote.quoteDateTime
    }
}

object Quotes : Table() {
    val text = text("content")
    val dateTime = datetime("quote_date_time")
    val votes = integer("votes")
    val md5Hash = varchar("md5_hash", length = 64).uniqueIndex(customIndexName = "UIX_quotes_md5_hash")

    override val primaryKey = PrimaryKey(md5Hash, name = "PK_quotes")
}

const val MAIN_PAGE = "https://bash.im"
const val PAGE_NAVIGATOR_URI = "/index/"
fun getPageByIndex(index: Int) = Jsoup.connect(MAIN_PAGE + PAGE_NAVIGATOR_URI + index.toString()).get()!!

fun Document.findAllQuotes(): List<Quote> =
    findAllQuoteFrames()
        .map { quoteFrame ->
            quoteFrame.extractPureQuotes()
        }

fun Element.extractPureQuotes(): Quote {
    val quoteText = this.findQuoteBody().textNodes().let { parseInternalText(it) }
    val dateTimeString = this.findQuoteDate().textNodes().let { parseInternalText(it) }
    val votesString = this.findVotesText().textNodes().let { parseInternalText(it) }

    val md5Hash = DigestUtils.md5Hex(quoteText).uppercase(Locale.getDefault())
    val votes = if (votesString.all { it.isDigit() }) votesString.toInt() else 0
    return Quote(
        text = quoteText,
        quoteDateTime = dateTimeString.parseDateTime(),
        votes = votes,
        md5Hash = md5Hash
    )
}

fun parseInternalText(textNodes: List<TextNode>): String = textNodes
    .mapNotNull { it.text() }
    .map { it.replace("\n", "") }
    .filter { it.isNotBlank() }
    .joinToString(separator = "\n")
    .trim()

fun isPageOnCurrentIndex(page: Document, index: Int): Boolean {
    val pager = page.select("div.pager input.pager__input")
    return pager.firstOrNull()?.attr("value")?.equals(index.toString()) ?: false
}

fun Document.findAllQuoteFrames() = this.select("section.quotes div.quote__frame")!!
fun Document.findNextLinks(): Elements? = this.select("a.pager__item")
fun Element.findQuoteBody() = this.select("div.quote__body")!!
fun Element.findQuoteDate() = this.select("div.quote__header_date")!!
fun Element.findVotesText() = this.select("div.quote__total")!!


data class Quote(
    val text: String,
    val quoteDateTime: LocalDateTime,
    val votes: Int,
    val md5Hash: String
)

val dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy H:mm")!!

fun String.parseDateTime(): LocalDateTime = this.replace(" в ", " ")
    .let { LocalDateTime.parse(it, dateTimeFormatter) }
