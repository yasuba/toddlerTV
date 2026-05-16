package kidstv.review

import cats.effect.{ExitCode, IO, Ref}
import javax.sql.DataSource
import kidstv.cli.Output
import kidstv.domain.*
import kidstv.storage.{Db, VideoRepo, TagOverrideRepo}
import java.time.Instant

object ReviewLoop:

  def run(ds: DataSource): IO[ExitCode] =
    for
      deferred <- Ref.of[IO, Set[String]](Set.empty)
      _        <- loop(ds, deferred)
    yield ExitCode.Success

  private def loop(ds: DataSource, deferred: Ref[IO, Set[String]]): IO[Unit] =
    for
      candidates <- Db.connectIO(ds)(VideoRepo.listCandidates())
      skip       <- deferred.get
      remaining   = candidates.filterNot(v => skip.contains(v.id))
      _ <- remaining.headOption match
        case None =>
          IO.println("No more candidates to review.")
        case Some(video) =>
          reviewOne(ds, video, deferred).flatMap:
            case Action.Quit => IO.unit
            case _           => loop(ds, deferred)
    yield ()

  private enum Action:
    case Approve, Reject, Defer, Skip, Quit

  private def reviewOne(ds: DataSource, video: Video, deferred: Ref[IO, Set[String]]): IO[Action] =
    for
      // Transition to Reviewing
      now <- IO.realTimeInstant
      reviewing = video.status match
        case c: VideoStatus.Candidate => c.startReview(now)
        case _ => VideoStatus.Reviewing(now)
      _ <- Db.transactIO(ds)(VideoRepo.updateStatus(video.id, reviewing, now))

      // Show video info
      _ <- IO.println(s"\n${"=" * 60}")
      _ <- IO.println(Output.printVideo(video.copy(status = reviewing)))
      _ <- IO.println(s"${"=" * 60}")

      // Play via mpv
      _ <- playVideo(video.id)

      // Prompt
      action <- prompt(ds, video, reviewing, deferred)
    yield action

  private def playVideo(id: String): IO[Unit] =
    for
      file <- IO.blocking:
        val dir = os.pwd / "videos"
        if os.exists(dir) then os.list(dir).find(_.last.startsWith(s"$id."))
        else None
      _ <- file match
        case Some(path) =>
          IO.println(s"Playing: ${path.last}") *>
          IO.blocking(os.proc("mpv", path.toString).call(check = false)).void
            .handleErrorWith(e => IO.println(s"  (mpv not available: ${e.getMessage})"))
        case None =>
          IO.println("  (no file downloaded — run 'sync' first to download)")
    yield ()

  private def prompt(ds: DataSource, video: Video, reviewing: VideoStatus.Reviewing, deferred: Ref[IO, Set[String]]): IO[Action] =
    IO.print("\n[a] approve  [r] reject  [d] defer  [s] skip  [t] edit tags  [q] quit\n> ") *>
    IO.blocking(scala.io.StdIn.readLine()).map(_.trim.toLowerCase).flatMap:
      case "a" | "approve" =>
        for
          _     <- IO.print("Notes (optional, press enter to skip): ")
          notes <- IO.blocking(scala.io.StdIn.readLine()).map(_.trim).map(n => Option.when(n.nonEmpty)(n))
          now   <- IO.realTimeInstant
          status = reviewing.approve(now, notes)
          _     <- Db.transactIO(ds)(VideoRepo.updateStatus(video.id, status, now))
          _     <- IO.println(s"  Approved: ${video.id}")
        yield Action.Approve

      case "r" | "reject" =>
        for
          _      <- IO.print("Reason (required): ")
          reason <- IO.blocking(scala.io.StdIn.readLine()).map(_.trim)
          result <-
            if reason.isEmpty then
              IO.println("  Reason is required.") *> prompt(ds, video, reviewing, deferred)
            else
              for
                now   <- IO.realTimeInstant
                status = reviewing.reject(now, reason)
                _     <- Db.transactIO(ds)(VideoRepo.updateStatus(video.id, status, now))
                _     <- IO.println(s"  Rejected: ${video.id} ($reason)")
              yield Action.Reject
        yield result

      case "d" | "defer" =>
        for
          now <- IO.realTimeInstant
          status = reviewing.defer(now)
          _   <- Db.transactIO(ds)(VideoRepo.updateStatus(video.id, status, now))
          _   <- deferred.update(_ + video.id)
          _   <- IO.println(s"  Deferred: ${video.id}")
        yield Action.Defer

      case "s" | "skip" =>
        for
          // Return to Candidate without marking as deferred
          now <- IO.realTimeInstant
          status = reviewing.defer(now)
          _   <- Db.transactIO(ds)(VideoRepo.updateStatus(video.id, status, now))
          _   <- IO.println(s"  Skipped: ${video.id}")
        yield Action.Skip

      case "t" | "tags" =>
        editTags(ds, video) *> prompt(ds, video, reviewing, deferred)

      case "q" | "quit" =>
        for
          // Return to Candidate before quitting
          now <- IO.realTimeInstant
          status = reviewing.defer(now)
          _   <- Db.transactIO(ds)(VideoRepo.updateStatus(video.id, status, now))
        yield Action.Quit

      case _ =>
        IO.println("  Unknown option.") *> prompt(ds, video, reviewing, deferred)

  private def editTags(ds: DataSource, video: Video): IO[Unit] =
    val current = video.tags
    for
      _ <- IO.println(s"\nCurrent tags: ${current.map(tagsOneLiner).getOrElse("(none)")}")
      _ <- IO.println("Enter field=value to override (e.g. pacing=Calm), or blank to cancel:")
      _ <- IO.print("> ")
      line <- IO.blocking(scala.io.StdIn.readLine()).map(_.trim)
      _ <- if line.isEmpty then IO.println("  No changes.")
           else line.split("=", 2) match
             case Array(field, value) =>
               val f = field.trim
               val v = value.trim
               Db.transactIO(ds)(upsertOverride(video.id, f, v)) *>
               IO.println(s"  Override saved: $f=$v")
             case _ =>
               IO.println("  Invalid format. Use field=value.")
    yield ()

  private def upsertOverride(videoId: String, field: String, value: String)(using com.augustnagro.magnum.DbCon): Unit =
    import com.augustnagro.magnum.*
    sql"""INSERT INTO tag_overrides (video_id, field_name, value)
      VALUES ($videoId, $field, $value)
      ON CONFLICT (video_id, field_name) DO UPDATE SET value = $value""".update.run()

  private def tagsOneLiner(t: VideoTags): String =
    s"pacing=${t.pacing} type=${t.contentType} moods=${t.moods.mkString(",")} age=${t.ageRange.minMonths}-${t.ageRange.maxMonths}mo music=${t.hasMusic} narration=${t.hasNarration}"
