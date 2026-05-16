package kidstv.domain

import java.time.Instant

case class Session(
  id: String,
  builtAt: Instant,
  targetDurationSeconds: Int,
  moodFilter: Option[Mood],
  videoIds: List[String]
)

case class PlayRecord(
  id: Long,
  videoId: String,
  playedAt: Instant,
  sessionId: Option[String],
  completed: Boolean
)
