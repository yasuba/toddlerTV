package kidstv.enrich

import cats.effect.IO
import io.circe.*
import io.circe.parser.decode
import kidstv.domain.*

object RuleBasedTagger:

  case class PartialTags(
    pacing: Option[Pacing] = None,
    contentType: Option[ContentType] = None,
    moods: Set[Mood] = Set.empty,
    ageRange: Option[AgeRange] = None,
    hasMusic: Option[Boolean] = None,
    hasNarration: Option[Boolean] = None
  )

  case class KeywordRule(keywords: Vector[String], apply: PartialTags)
  case class DurationBucket(name: String, maxSeconds: Option[Int], apply: PartialTags)

  case class TagRules(
    channelDefaults: Map[String, PartialTags],
    keywordRules: Vector[KeywordRule],
    durationBuckets: Vector[DurationBucket]
  )

  private given Decoder[Pacing] = Decoder.decodeString.emap: s =>
    scala.util.Try(Pacing.valueOf(s)).toEither.left.map(_ => s"Invalid Pacing: $s")

  private given Decoder[ContentType] = Decoder.decodeString.emap: s =>
    scala.util.Try(ContentType.valueOf(s)).toEither.left.map(_ => s"Invalid ContentType: $s")

  private given Decoder[Mood] = Decoder.decodeString.emap: s =>
    scala.util.Try(Mood.valueOf(s)).toEither.left.map(_ => s"Invalid Mood: $s")

  private given Decoder[AgeRange] = Decoder.instance: c =>
    for
      min <- c.get[Int]("minMonths")
      max <- c.get[Int]("maxMonths")
    yield AgeRange(min, max)

  private given Decoder[PartialTags] = Decoder.instance: c =>
    for
      pacing      <- c.get[Option[Pacing]]("pacing")
      contentType <- c.get[Option[ContentType]]("contentType")
      moods       <- c.getOrElse[Set[Mood]]("moods")(Set.empty)
      ageRange    <- c.get[Option[AgeRange]]("ageRange")
      hasMusic    <- c.get[Option[Boolean]]("hasMusic")
      hasNarration <- c.get[Option[Boolean]]("hasNarration")
    yield PartialTags(pacing, contentType, moods, ageRange, hasMusic, hasNarration)

  private given Decoder[KeywordRule] = Decoder.instance: c =>
    for
      keywords <- c.get[Vector[String]]("keywords")
      apply    <- c.get[PartialTags]("apply")
    yield KeywordRule(keywords, apply)

  private given Decoder[DurationBucket] = Decoder.instance: c =>
    for
      name       <- c.get[String]("name")
      maxSeconds <- c.get[Option[Int]]("maxSeconds")
      apply      <- c.get[PartialTags]("apply")
    yield DurationBucket(name, maxSeconds, apply)

  private given Decoder[TagRules] = Decoder.instance: c =>
    for
      channels <- c.getOrElse[Map[String, PartialTags]]("channelDefaults")(Map.empty)
      keywords <- c.getOrElse[Vector[KeywordRule]]("keywordRules")(Vector.empty)
      duration <- c.getOrElse[Vector[DurationBucket]]("durationBuckets")(Vector.empty)
    yield TagRules(channels, keywords, duration)

  def loadRules(path: os.Path): IO[TagRules] =
    IO.blocking(os.read(path)).flatMap: content =>
      IO.fromEither(decode[TagRules](content).left.map(e =>
        RuntimeException(s"Failed to parse tag rules: $e")))

  def extractTags(rules: TagRules, metadata: Map[String, Json]): IO[VideoTags] =
    IO.pure(applyRules(rules, metadata))

  private def applyRules(rules: TagRules, metadata: Map[String, Json]): VideoTags =
    val ytJson = metadata.getOrElse("youtube", Json.obj())
    val cursor = ytJson.hcursor

    val title = cursor.get[String]("title").getOrElse("")
    val description = cursor.get[String]("description").getOrElse("")
    val channel = cursor.get[String]("channel").getOrElse("")
    val durationSec = cursor.get[Double]("duration").toOption.map(_.toInt)

    val channelTags = rules.channelDefaults.get(channel).getOrElse(PartialTags())
    val keywordTags = matchKeywords(rules.keywordRules, title, description)
    val durationTags = matchDuration(rules.durationBuckets, durationSec)

    resolve(channelTags, keywordTags, durationTags)

  private def matchKeywords(rules: Vector[KeywordRule], title: String, description: String): PartialTags =
    val titleLower = title.toLowerCase
    val descLower = description.toLowerCase

    rules.foldLeft(PartialTags()): (acc, rule) =>
      val titleHits = rule.keywords.count(kw => titleLower.contains(kw.toLowerCase))
      val descHits = rule.keywords.count(kw => descLower.contains(kw.toLowerCase))
      if titleHits >= 1 || descHits >= 2 then merge(acc, rule.apply)
      else acc

  private def matchDuration(buckets: Vector[DurationBucket], durationSec: Option[Int]): PartialTags =
    durationSec match
      case None => PartialTags()
      case Some(sec) =>
        val sorted = buckets.sortBy(_.maxSeconds.getOrElse(Int.MaxValue))
        sorted.find(b => b.maxSeconds.forall(sec <= _))
          .map(_.apply)
          .getOrElse(PartialTags())

  private def merge(base: PartialTags, overlay: PartialTags): PartialTags =
    PartialTags(
      pacing = overlay.pacing.orElse(base.pacing),
      contentType = overlay.contentType.orElse(base.contentType),
      moods = base.moods ++ overlay.moods,
      ageRange = overlay.ageRange.orElse(base.ageRange),
      hasMusic = overlay.hasMusic.orElse(base.hasMusic),
      hasNarration = overlay.hasNarration.orElse(base.hasNarration)
    )

  private val defaults = VideoTags(
    pacing = Pacing.Moderate,
    contentType = ContentType.Mixed,
    moods = Set.empty,
    ageRange = AgeRange(18, 60),
    hasMusic = false,
    hasNarration = false
  )

  private def resolve(channel: PartialTags, keywords: PartialTags, duration: PartialTags): VideoTags =
    val merged = merge(merge(duration, keywords), channel)
    VideoTags(
      pacing = merged.pacing.getOrElse(defaults.pacing),
      contentType = merged.contentType.getOrElse(defaults.contentType),
      moods = if merged.moods.nonEmpty then merged.moods else defaults.moods,
      ageRange = merged.ageRange.getOrElse(defaults.ageRange),
      hasMusic = merged.hasMusic.getOrElse(defaults.hasMusic),
      hasNarration = merged.hasNarration.getOrElse(defaults.hasNarration)
    )
