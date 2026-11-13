package kotobaya.android.intake

import kotobaya.android.di.FeatureEntry
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notes.NoteRequest
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.koin.core.context.GlobalContext

/** Production [IntakeServices] over the Koin singletons. */
class KoinIntakeServices : IntakeServices {
    private val koin get() = GlobalContext.get()

    override fun create(request: NoteRequest): CreateResult = koin.get<NoteCreator<SqlLearningTables>>().create(request)

    override fun decks(): List<DeckOption> = koin.get<SqlDelightDeviceStore>().read { tx ->
        tx.learning.where(LearningTable.DECK, "type", JsonString("user"))
            .filter { (it["deleted_at"] ?: JsonNull) == JsonNull }
            .mapNotNull { row ->
                val id = (row["id"] as? JsonString)?.value ?: return@mapNotNull null
                DeckOption(id, (row["name"] as? JsonString)?.value ?: id)
            }
    }.sortedBy { it.name }
}

/** Test seam: replace [factory] to give [IntakeActivity] a fake. */
object IntakeServicesProvider {
    @Volatile
    var factory: () -> IntakeServices = { KoinIntakeServices() }

    fun get(): IntakeServices = factory()
}

/** THE registration for featureEntries. The activity is declared in the manifest. */
val intakeFeature = FeatureEntry(id = "intake")
