package kidstv.domain

enum Pacing:
  case Calm, Moderate, Stimulating, Hyperactive

enum ContentType:
  case Narrative, Musical, Educational, Mixed

enum Mood:
  case WindDown, Energetic, Curious, Silly, Cosy

case class AgeRange(minMonths: Int, maxMonths: Int)

case class VideoTags(
  pacing: Pacing,
  contentType: ContentType,
  moods: Set[Mood],
  ageRange: AgeRange,
  hasMusic: Boolean,
  hasNarration: Boolean
)
