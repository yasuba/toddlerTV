package kidstv.domain

import java.time.Instant

// Sealed trait instead of enum so transition methods live on each subtype.
// You can only call e.g. .approve() when you've matched a Reviewing —
// calling it on a Candidate won't compile.

sealed trait VideoStatus

object VideoStatus:
  case class Candidate(addedAt: Instant) extends VideoStatus:
    def startReview(at: Instant): Reviewing = Reviewing(at)

  case class Reviewing(startedAt: Instant) extends VideoStatus:
    def approve(at: Instant, notes: Option[String] = None): Approved = Approved(at, notes)
    def reject(at: Instant, reason: String): Rejected = Rejected(at, reason)
    def defer(at: Instant): Candidate = Candidate(at)

  case class Approved(reviewedAt: Instant, notes: Option[String]) extends VideoStatus:
    def retire(at: Instant, reason: String): Retired = Retired(at, reason)

  case class Rejected(reviewedAt: Instant, reason: String) extends VideoStatus

  case class Retired(retiredAt: Instant, reason: String) extends VideoStatus:
    def unretire(at: Instant, notes: Option[String] = None): Approved = Approved(at, notes)

case class Video(
  id: String,
  sourceUrl: String,
  title: String,
  runtimeSeconds: Option[Int],
  status: VideoStatus,
  tags: Option[VideoTags],
  createdAt: Instant,
  updatedAt: Instant
)
