package kidstv.domain

import io.circe.*
import io.circe.syntax.*
import io.circe.generic.semiauto.*
import java.time.Instant
import scala.util.Try

object Codecs:

  // Instant as ISO-8601 string
  given Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  given Decoder[Instant] = Decoder.decodeString.emap: s =>
    Try(Instant.parse(s)).toEither.left.map(_.getMessage)

  // Simple enums as strings
  given Encoder[Pacing] = Encoder.encodeString.contramap(_.toString)
  given Decoder[Pacing] = Decoder.decodeString.emap: s =>
    Try(Pacing.valueOf(s)).toEither.left.map(_ => s"Invalid Pacing: $s")

  given Encoder[ContentType] = Encoder.encodeString.contramap(_.toString)
  given Decoder[ContentType] = Decoder.decodeString.emap: s =>
    Try(ContentType.valueOf(s)).toEither.left.map(_ => s"Invalid ContentType: $s")

  given Encoder[Mood] = Encoder.encodeString.contramap(_.toString)
  given Decoder[Mood] = Decoder.decodeString.emap: s =>
    Try(Mood.valueOf(s)).toEither.left.map(_ => s"Invalid Mood: $s")

  given Encoder[AgeRange] = deriveEncoder
  given Decoder[AgeRange] = deriveDecoder

  given Encoder[VideoTags] = deriveEncoder
  given Decoder[VideoTags] = deriveDecoder

  // VideoStatus — discriminated union via "type" field
  given Encoder[VideoStatus] = Encoder.instance:
    case s: VideoStatus.Candidate =>
      Json.obj("type" -> "Candidate".asJson, "addedAt" -> s.addedAt.asJson)
    case s: VideoStatus.Reviewing =>
      Json.obj("type" -> "Reviewing".asJson, "startedAt" -> s.startedAt.asJson)
    case s: VideoStatus.Approved =>
      Json.obj("type" -> "Approved".asJson, "reviewedAt" -> s.reviewedAt.asJson, "notes" -> s.notes.asJson)
    case s: VideoStatus.Rejected =>
      Json.obj("type" -> "Rejected".asJson, "reviewedAt" -> s.reviewedAt.asJson, "reason" -> s.reason.asJson)
    case s: VideoStatus.Retired =>
      Json.obj("type" -> "Retired".asJson, "retiredAt" -> s.retiredAt.asJson, "reason" -> s.reason.asJson)

  given Decoder[VideoStatus] = Decoder.instance: c =>
    c.get[String]("type").flatMap:
      case "Candidate" => c.get[Instant]("addedAt").map(VideoStatus.Candidate(_))
      case "Reviewing" => c.get[Instant]("startedAt").map(VideoStatus.Reviewing(_))
      case "Approved" =>
        for
          at    <- c.get[Instant]("reviewedAt")
          notes <- c.get[Option[String]]("notes")
        yield VideoStatus.Approved(at, notes)
      case "Rejected" =>
        for
          at     <- c.get[Instant]("reviewedAt")
          reason <- c.get[String]("reason")
        yield VideoStatus.Rejected(at, reason)
      case "Retired" =>
        for
          at     <- c.get[Instant]("retiredAt")
          reason <- c.get[String]("reason")
        yield VideoStatus.Retired(at, reason)
      case other => Left(DecodingFailure(s"Unknown VideoStatus type: $other", c.history))
