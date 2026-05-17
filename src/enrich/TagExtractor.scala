package kidstv.enrich

import io.circe.parser.decode
import kidstv.domain.*
import kidstv.domain.Codecs.given

object TagExtractor:

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
