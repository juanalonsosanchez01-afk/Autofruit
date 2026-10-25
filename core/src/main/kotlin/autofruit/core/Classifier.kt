package autofruit.core

/** Gives every tile of a frame a fruit type id, using and growing [library]. */
class Classifier(private val library: TypeLibrary) {
    fun classify(frame: Frame, learn: Boolean = true) {
        for (t in frame.tray) t.type = library.identifyLit(t.descriptor, learn)
        for (t in frame.board) if (t.lit) t.type = library.identifyLit(t.descriptor, learn)
        for (t in frame.board) if (!t.lit) t.type = library.identifyCovered(t.descriptor)
    }
}
