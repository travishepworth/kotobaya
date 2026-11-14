package kotobaya.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/** Captures one logger's events (MDC frozen at log time) and renders them with the production JSON encoder. */
class LogCapture(name: String) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(name) as Logger
    val events = CopyOnWriteArrayList<ILoggingEvent>()
    private val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(e: ILoggingEvent) {
            e.prepareForDeferredProcessing()
            events += e
        }
    }
    private val encoder = LogstashEncoder().apply {
        context = logger.loggerContext
        start()
    }

    init {
        appender.context = logger.loggerContext
        appender.start()
        logger.addAppender(appender)
    }

    /** Every captured event as the JSON line production would write (parsing proves it is one JSON object). */
    fun lines(): List<JsonNode> = events.map { json.readTree(encoder.encode(it)) }

    override fun close() {
        logger.detachAppender(appender)
    }
}
