package kidstv.session

import kidstv.domain.*

object Builder:

  case class Config(
    targetSeconds: Int,
    moodFilter: Option[Mood],
    recentlyPlayed: Set[String],
    avoidRepeats: Int = 3
  )

  def selectVideos(approved: Vector[Video], config: Config): Vector[Video] =
    val eligible = approved
      .filter(v => !config.recentlyPlayed.contains(v.id))
      .filter(v => config.moodFilter.forall(mood => v.tags.exists(_.moods.contains(mood))))
      .filter(_.runtimeSeconds.isDefined)

    if eligible.isEmpty then return Vector.empty

    val target = config.targetSeconds
    val tolerance = target * 0.1

    // Greedy selection: pick videos, balancing shows (don't pick too many from same show)
    val shuffled = scala.util.Random.shuffle(eligible)
    var selected = Vector.empty[Video]
    var totalSeconds = 0
    var showCounts = Map.empty[String, Int]

    for video <- shuffled do
      val runtime = video.runtimeSeconds.get
      if totalSeconds + runtime <= target + tolerance then
        val show = showName(video.title)
        val count = showCounts.getOrElse(show, 0)
        // Skip if we already have 2 from the same show and there are other options
        if count < 2 || eligible.size <= selected.size + 2 then
          selected = selected :+ video
          totalSeconds += runtime
          showCounts = showCounts.updated(show, count + 1)

    // Try to end on a calm/wind-down video
    if selected.size > 1 then
      val lastIdx = selected.indices.findLast: i =>
        selected(i).tags.exists: t =>
          t.pacing == Pacing.Calm || t.moods.contains(Mood.WindDown)
      lastIdx match
        case Some(idx) if idx != selected.size - 1 =>
          val calm = selected(idx)
          val reordered = selected.patch(idx, Nil, 1) :+ calm
          reordered
        case _ => selected
    else selected

  // Extract show name heuristic: everything before the first " - " separator
  private def showName(title: String): String =
    title.split(" - ").headOption.getOrElse(title).trim.toLowerCase
