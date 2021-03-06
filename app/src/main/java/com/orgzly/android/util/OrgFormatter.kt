package com.orgzly.android.util

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import android.view.View
import com.orgzly.android.ActionService
import com.orgzly.android.AppIntent
import com.orgzly.android.prefs.AppPreferences
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 *
 */
object OrgFormatter {
    private const val LINK_SCHEMES = "https?|mailto|tel|voicemail|geo|sms|smsto|mms|mmsto"

    // tel:1234567
    private const val PLAIN_LINK = "(($LINK_SCHEMES):\\S+)"

    /* Same as the above, but ] ends the link too. Used for bracket links. */
    private const val BRACKET_LINK = "(($LINK_SCHEMES):[^]\\s]+)"

    // #custom id
    private const val CUSTOM_ID_LINK = "(#([^]]+))"

    // id:CABA8098-5969-429E-A780-94C8E0A9D206
    private const val HD = "[0-9a-fA-F]"
    private const val ID_LINK = "(id:($HD{8}-(?:$HD{4}-){3}$HD{12}))"

    /* Allows anything as a link. Probably needs some constraints.
     * See http://orgmode.org/manual/External-links.html and org-any-link-re
     */
    private const val BRACKET_ANY_LINK = "(([^]]+))"

    private const val PRE = "- \t('\"{"
    private const val POST = "- \\t.,:!?;'\")}\\["
    private const val BORDER = "\\S"
    private const val BODY = ".*?(?:\n.*?)?"

    // Added .{0} for the next find() to match from the beginning
    private fun markupRegex(marker: Char): String =
            "(?:^|.{0}|[$PRE])([$marker]($BORDER|$BORDER$BODY$BORDER)[$marker])(?:[$POST]|$)"

    private val MARKUP_PATTERN = Pattern.compile(
            markupRegex('*') + "|" +
                    markupRegex('/') + "|" +
                    markupRegex('_') + "|" +
                    markupRegex('=') + "|" +
                    markupRegex('~') + "|" +
                    markupRegex('+'), Pattern.MULTILINE)

    private fun linkPattern(str: String) = Pattern.compile(str)
    private fun bracketLinkPattern(str: String) = Pattern.compile("\\[\\[$str]]")
    private fun namedBracketLinkPattern(str: String) = Pattern.compile("\\[\\[$str]\\[([^]]+)]]")

    private const val FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    data class SpanRegion(val start: Int, val end: Int, val content: String, val span: Any? = null)

    data class Config(val style: Boolean = true, val withMarks: Boolean = false, val linkify: Boolean = true) {
        constructor(context: Context, linkify: Boolean): this(
                AppPreferences.styleText(context),
                AppPreferences.styledTextWithMarks(context),
                linkify)
    }

    @JvmOverloads
    fun parse(str: String, context: Context? = null, linkify: Boolean = true): SpannableStringBuilder {
        val config = if (context == null) {
            Config(linkify = linkify)
        } else {
            Config(context, linkify)
        }

        return this.parse(str, config)
    }

    fun parse(str: String, config: Config): SpannableStringBuilder {
        var ssb = SpannableStringBuilder(str)

        ssb = parsePropertyLinks(ssb, CUSTOM_ID_LINK, "CUSTOM_ID", config.linkify)
        ssb = parsePropertyLinks(ssb, ID_LINK, "ID", config.linkify)

        ssb = parseOrgLinksWithName(ssb, BRACKET_LINK, config.linkify)
        ssb = parseOrgLinksWithName(ssb, BRACKET_ANY_LINK, false)

        ssb = parseOrgLinks(ssb, BRACKET_LINK, config.linkify)

        parsePlainLinks(ssb, PLAIN_LINK, config.linkify)

        ssb = parseMarkup(ssb, config)

        return ssb
    }

//    private fun logSpans(ssb: SpannableStringBuilder) {
//        val spans = ssb.getSpans(0, ssb.length - 1, Any::class.java)
//        LogUtils.d(TAG, "--- Spans ---", spans.size)
//        spans.forEach {
//            LogUtils.d(TAG, "Span", it, it.javaClass.simpleName, ssb.getSpanStart(it), ssb.getSpanEnd(it))
//        }
//    }

    /**
     * [[ http://link.com ][ name ]]
     */
    private fun parseOrgLinksWithName(ssb: SpannableStringBuilder, linkRegex: String, linkify: Boolean): SpannableStringBuilder {
        val p = namedBracketLinkPattern(linkRegex)
        val m = p.matcher(ssb)

        return collectRegions(ssb) { spanRegions ->
            while (m.find()) {
                val link = m.group(1)
                val name = m.group(3)

                val span = if (linkify) URLSpan(link) else null

                spanRegions.add(SpanRegion(m.start(), m.end(), name, span))
            }
        }
    }

    /**
     * [[ http://link.com ]]
     */
    private fun parseOrgLinks(ssb: SpannableStringBuilder, linkRegex: String, linkify: Boolean): SpannableStringBuilder {
        val p = bracketLinkPattern(linkRegex)
        val m = p.matcher(ssb)

        return collectRegions(ssb) { spanRegions ->
            while (m.find()) {
                val link = m.group(1)

                val span = if (linkify) URLSpan(link) else null

                spanRegions.add(SpanRegion(m.start(), m.end(), link, span))
            }
        }
    }

    /**
     * [[ #custom id ]] and [[ #custom id ][ link ]]
     * [[ id:id ]] and [[ id:id ][ link ]]
     */
    private fun parsePropertyLinks(ssb: SpannableStringBuilder, linkRegex: String, propName: String, linkify: Boolean): SpannableStringBuilder {
        var builder = parsePropertyLinkType(ssb, linkify, propName, namedBracketLinkPattern(linkRegex), 2, 3)
        builder = parsePropertyLinkType(builder, linkify, propName, bracketLinkPattern(linkRegex), 2, 1)

        return builder
    }

    private fun parsePropertyLinkType(
            ssb: SpannableStringBuilder,
            linkify: Boolean,
            propName: String,
            p: Pattern,
            propGroup: Int,
            linkGroup: Int): SpannableStringBuilder {

        val m = p.matcher(ssb)

        return collectRegions(ssb) { spanRegions ->
            while (m.find()) {
                val link = m.group(linkGroup)
                val propValue = m.group(propGroup)

                ssb.replace(m.start(), m.end(), link)

                val span = if (linkify) {
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            val intent = Intent(widget.context, ActionService::class.java)
                            intent.action = AppIntent.ACTION_OPEN_NOTE
                            intent.putExtra(AppIntent.EXTRA_PROPERTY_NAME, propName)
                            intent.putExtra(AppIntent.EXTRA_PROPERTY_VALUE, propValue)
                            ActionService.enqueueWork(widget.context, intent)
                        }
                    }

                } else {
                    null
                }

                spanRegions.add(SpanRegion(m.start(), m.end(), link, span))
            }
        }
    }

    /**
     * http://link.com
     */
    private fun parsePlainLinks(ssb: SpannableStringBuilder, linkRegex: String, linkify: Boolean) {
        if (linkify) {
            val p = linkPattern(linkRegex)
            val m = p.matcher(ssb)

            while (m.find()) {
                val link = m.group(1)

                // Make sure first character has no URLSpan already
                if (ssb.getSpans(m.start(), m.start() + 1, URLSpan::class.java).isEmpty()) {
                    ssb.setSpan(URLSpan(link), m.start(), m.end(), FLAGS)
                }
            }
        }
    }

    enum class SpanType {
        BOLD,
        ITALIC,
        UNDERLINE,
        MONOSPACE,
        STRIKETHROUGH
    }

    private fun newSpan(type: SpanType): Any {
        return when (type) {
            SpanType.BOLD -> StyleSpan(Typeface.BOLD)
            SpanType.ITALIC -> StyleSpan(Typeface.ITALIC)
            SpanType.UNDERLINE -> UnderlineSpan()
            SpanType.MONOSPACE -> TypefaceSpan("monospace")
            SpanType.STRIKETHROUGH -> StrikethroughSpan()
        }
    }

    private fun parseMarkup(ssb: SpannableStringBuilder, config: Config): SpannableStringBuilder {
        if (!config.style) {
            return ssb
        }

        val spanRegions: MutableList<SpanRegion> = mutableListOf()

        fun setMarkupSpan(matcher: Matcher, group: Int, spanType: SpanType) {
            // if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Type matched", withMarks, matcher.start(group), matcher.end(group))

            if (config.withMarks) {
                ssb.setSpan(newSpan(spanType), matcher.start(group), matcher.end(group), FLAGS)

            } else {
                // Next group matches content only, without markers.
                val content = matcher.group(group + 1)

                spanRegions.add(SpanRegion(matcher.start(group), matcher.end(group), content, newSpan(spanType)))
            }
        }

        val m = MARKUP_PATTERN.matcher(ssb)

        while (m.find()) {
            // if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Matched", ssb.toString(), MARKUP_PATTERN, m.groupCount(), m.group(), m.start(), m.end())

            when {
                m.group(1)  != null -> setMarkupSpan(m,  1, SpanType.BOLD)
                m.group(3)  != null -> setMarkupSpan(m,  3, SpanType.ITALIC)
                m.group(5)  != null -> setMarkupSpan(m,  5, SpanType.UNDERLINE)
                m.group(7)  != null -> setMarkupSpan(m,  7, SpanType.MONOSPACE)
                m.group(9)  != null -> setMarkupSpan(m,  9, SpanType.MONOSPACE)
                m.group(11) != null -> setMarkupSpan(m, 11, SpanType.STRIKETHROUGH)
            }
        }

        return buildFromRegions(ssb, spanRegions)
    }


    private fun collectRegions(ssb: SpannableStringBuilder, collect: (MutableList<SpanRegion>) -> Any): SpannableStringBuilder {
        val spanRegions: MutableList<SpanRegion> = mutableListOf()
        collect(spanRegions)
        return buildFromRegions(ssb, spanRegions)
    }

    private fun buildFromRegions(ssb: SpannableStringBuilder, spanRegions: MutableList<SpanRegion>): SpannableStringBuilder {
        if (spanRegions.isNotEmpty()) {
            val builder = SpannableStringBuilder()

            var pos = 0

            spanRegions.forEach { region ->
                // Append everything before region
                if (region.start > pos) {
                    builder.append(ssb.subSequence(pos, region.start))
                }

                // Create spanned string
                val str = SpannableString(region.content)

                if (region.span != null) {
                    str.setSpan(region.span, 0, str.length, FLAGS)
                }

                // Append spanned string
                builder.append(str)

                // Move current position after region
                pos = region.end
            }

            // Append the rest
            if (pos < ssb.length) {
                builder.append(ssb.subSequence(pos, ssb.length))
            }

            return builder

        } else {
            return ssb
        }
    }

    // private val TAG = OrgFormatter::class.java.name
}
