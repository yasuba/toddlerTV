package kidstv.enrich

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import io.circe.syntax.*
import kidstv.domain.*
import kidstv.domain.Codecs.given

object TagExtractor:

  def loadPrompt: IO[String] =
    IO.blocking:
      val stream = getClass.getResourceAsStream("/extract_tags.md")
      if stream == null then throw RuntimeException("extract_tags.md not found on classpath")
      try scala.io.Source.fromInputStream(stream).mkString
      finally stream.close()

  def extractTags(client: LlmClient, prompt: String, metadata: Map[String, Json]): IO[VideoTags] =
    val userMessage = metadata.map((source, json) =>
      s"## Source: $source\n${json.spaces2}"
    ).mkString("\n\n")
    // TODO: plug in additional metadata sources (TMDB, etc.) here
    for
      raw <- client.complete(prompt, userMessage)
      tags <- IO.fromEither(parseResponse(raw))
    yield tags

  private def parseResponse(raw: String): Either[Throwable, VideoTags] =
    val cleaned = raw.trim
      .stripPrefix("```json").stripPrefix("```")
      .stripSuffix("```")
      .trim
    decode[VideoTags](cleaned).left.map: err =>
      RuntimeException(s"Failed to parse LLM response as VideoTags: $err\nRaw: ${raw.take(500)}")

  def applyOverrides(tags: VideoTags, overrides: Vector[(String, String)]): VideoTags =
    overrides.foldLeft(tags): (t, override_) =>
      val (field, value) = override_
      field match
        case "pacing" =>
          decode[Pacing](s"\"$value\"").fold(_ => t, p => t.copy(pacing = p))
        case "contentType" =>
          decode[ContentType](s"\"$value\"").fold(_ => t, c => t.copy(contentType = c))
        case "moods" =>
          decode[Set[Mood]](value).fold(_ => t, m => t.copy(moods = m))
        case "ageRange" =>
          decode[AgeRange](value).fold(_ => t, a => t.copy(ageRange = a))
        case "hasMusic" =>
          decode[Boolean](value).fold(_ => t, b => t.copy(hasMusic = b))
        case "hasNarration" =>
          decode[Boolean](value).fold(_ => t, b => t.copy(hasNarration = b))
        case unknown =>
          scribe.warn(s"Unknown tag override field: $unknown")
          t
