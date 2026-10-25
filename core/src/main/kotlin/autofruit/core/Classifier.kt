package autofruit.core

/** Gives every tile of a frame a fruit type id, using and growing [library]. */
class Classifier(private val library: TypeLibrary) {
    fun classify(frame: Frame, learn: Boolean = true) {
        for (t in frame.tray) t.type = library.identifyLit(t.descriptor, learn)
        for (t in frame.board) if (t.lit) t.type = library.identifyLit(t.descriptor, learn)
        for (t in frame.board) if (!t.lit) t.type = library.identifyCovered(t.descriptor)
    }

    /** Learns the lit fruits [classify] could not place, then re-reads the covered tiles if any were new. */
    fun learnMissing(frame: Frame) {
        val missing = (frame.tray + frame.board.filter { it.lit }).filter { it.type < 0 }
        if (missing.isEmpty()) return
        for (t in missing) t.type = library.identifyLit(t.descriptor, true)
        for (t in frame.board) if (!t.lit) t.type = library.identifyCovered(t.descriptor)
    }
}
