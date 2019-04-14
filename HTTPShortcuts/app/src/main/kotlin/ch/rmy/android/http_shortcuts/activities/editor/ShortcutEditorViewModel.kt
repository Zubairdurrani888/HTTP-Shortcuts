package ch.rmy.android.http_shortcuts.activities.editor

import android.app.Application
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import ch.rmy.android.http_shortcuts.R
import ch.rmy.android.http_shortcuts.data.RealmViewModel
import ch.rmy.android.http_shortcuts.data.Repository
import ch.rmy.android.http_shortcuts.data.livedata.ListLiveData
import ch.rmy.android.http_shortcuts.data.models.HasId.Companion.FIELD_ID
import ch.rmy.android.http_shortcuts.data.models.Header
import ch.rmy.android.http_shortcuts.data.models.Shortcut
import ch.rmy.android.http_shortcuts.data.models.Shortcut.Companion.TEMPORARY_ID
import ch.rmy.android.http_shortcuts.data.models.Variable
import ch.rmy.android.http_shortcuts.extensions.commitAsync
import ch.rmy.android.http_shortcuts.extensions.context
import ch.rmy.android.http_shortcuts.extensions.getString
import ch.rmy.android.http_shortcuts.extensions.toLiveData
import ch.rmy.android.http_shortcuts.icons.Icons
import ch.rmy.android.http_shortcuts.utils.UUIDUtils.newUUID
import ch.rmy.android.http_shortcuts.utils.Validation
import ch.rmy.curlcommand.CurlCommand
import io.reactivex.Completable
import io.reactivex.Single
import io.realm.Realm
import io.realm.kotlin.where

class ShortcutEditorViewModel(application: Application) : RealmViewModel(application) {

    fun init(categoryId: String?, shortcutId: String?, curlCommand: CurlCommand?): Completable {
        if (isInitialized) {
            return Completable.complete()
        }
        this.categoryId = categoryId
        this.shortcutId = shortcutId
        return persistedRealm.commitAsync { realm ->
            val shortcut = if (shortcutId == null) {
                realm.copyToRealmOrUpdate(Shortcut.createNew(id = TEMPORARY_ID, iconName = randomInitialIconName))
            } else {
                Repository.copyShortcut(realm, Repository.getShortcutById(realm, shortcutId)!!, TEMPORARY_ID)
            }

            curlCommand?.let { curlCommand ->
                shortcut.method = curlCommand.method
                shortcut.url = curlCommand.url
                shortcut.username = curlCommand.username
                shortcut.password = curlCommand.password
                if (curlCommand.username.isNotEmpty() || curlCommand.password.isNotEmpty()) {
                    shortcut.authentication = Shortcut.AUTHENTICATION_BASIC
                }
                shortcut.timeout = curlCommand.timeout
                shortcut.bodyContent = curlCommand.data
                shortcut.requestBodyType = Shortcut.REQUEST_BODY_TYPE_CUSTOM_TEXT
                curlCommand.headers.forEach { (key, value) ->
                    shortcut.headers.add(realm.copyToRealm(Header(key = key, value = value)))
                }
            }

        }
            .doOnComplete {
                isInitialized = true
            }
    }

    var isInitialized: Boolean = false
        private set

    private var categoryId: String? = null
    private var shortcutId: String? = null

    val shortcut: LiveData<Shortcut?>
        get() = persistedRealm
            .where<Shortcut>()
            .equalTo(FIELD_ID, TEMPORARY_ID)
            .findFirstAsync()
            .toLiveData()

    private val randomInitialIconName by lazy {
        Icons.getRandomIcon(application)
    }

    fun hasChanges(): Boolean {
        val oldShortcut = shortcutId
            ?.let { Repository.getShortcutById(persistedRealm, it)!! }
            ?: Shortcut.createNew(iconName = randomInitialIconName)
        val newShortcut = getShortcut(persistedRealm) ?: return false
        return !newShortcut.isSameAs(oldShortcut)
    }

    fun setNameAndDescription(name: String, description: String): Completable =
        persistedRealm.commitAsync { realm ->
            getShortcut(realm)?.apply {
                this.name = name
                this.description = description
            }
        }

    fun setIconName(iconName: String?): Completable =
        persistedRealm.commitAsync { realm ->
            getShortcut(realm)?.apply {
                this.iconName = iconName
            }
        }

    private fun getShortcut(realm: Realm): Shortcut? =
        Repository.getShortcutById(realm, TEMPORARY_ID)

    fun trySave(): Single<String> {
        val id = shortcutId ?: newUUID()
        return persistedRealm.commitAsync { realm ->
            val shortcut = Repository.getShortcutById(realm, TEMPORARY_ID)!!
            validateShortcut(shortcut)

            val newShortcut = Repository.copyShortcut(realm, shortcut, id)
            if (shortcutId == null && categoryId != null) {
                Repository.getCategoryById(realm, categoryId!!)
                    ?.shortcuts
                    ?.add(newShortcut)
            }

            Repository.deleteShortcut(realm, TEMPORARY_ID)
        }
            .andThen(Single.just(id))
    }

    private fun validateShortcut(shortcut: Shortcut) {
        if (shortcut.name.isBlank()) {
            throw ShortcutValidationError(VALIDATION_ERROR_EMPTY_NAME)
        }
        if (!Validation.isAcceptableUrl(shortcut.url)) {
            throw ShortcutValidationError(VALIDATION_ERROR_INVALID_URL)
        }
    }

    // TODO: Find a way to not having to pass in 'shortcut'
    fun getBasicSettingsSubtitle(shortcut: Shortcut): CharSequence =
        if (shortcut.url.isEmpty() || shortcut.url == "http://") {
            getString(R.string.subtitle_basic_request_settings_prompt)
        } else {
            getString(
                R.string.subtitle_basic_request_settings_pattern,
                shortcut.method,
                shortcut.url
            )
        }

    fun getHeadersSettingsSubtitle(shortcut: Shortcut): CharSequence =
        getQuantityString(
            shortcut.headers.size,
            R.string.subtitle_request_headers_none,
            R.plurals.subtitle_request_headers_pattern
        )

    fun getRequestBodySettingsSubtitle(shortcut: Shortcut): CharSequence =
        if (shortcut.allowsBody()) {
            when (shortcut.requestBodyType) {
                Shortcut.REQUEST_BODY_TYPE_FORM_DATA,
                Shortcut.REQUEST_BODY_TYPE_X_WWW_FORM_URLENCODE -> getQuantityString(
                    shortcut.parameters.size,
                    R.string.subtitle_request_body_params_none,
                    R.plurals.subtitle_request_body_params_pattern
                )
                else -> if (shortcut.bodyContent.isBlank()) {
                    getString(R.string.subtitle_request_body_none)
                } else {
                    getString(R.string.subtitle_request_body_custom)
                }
            }
        } else {
            getString(R.string.subtitle_request_body_not_available, shortcut.method)
        }

    fun getAuthenticationSettingsSubtitle(shortcut: Shortcut): CharSequence =
        when (shortcut.authentication) {
            Shortcut.AUTHENTICATION_BASIC -> getString(R.string.subtitle_authentication_basic)
            Shortcut.AUTHENTICATION_DIGEST -> getString(R.string.subtitle_authentication_digest)
            else -> getString(R.string.subtitle_authentication_none)
        }

    fun getPreRequestActionsSettingsSubtitle(shortcut: Shortcut): CharSequence =
        getQuantityString(
            shortcut.beforeActions.size,
            R.string.subtitle_actions_none,
            R.plurals.subtitle_actions_pattern
        )

    fun getPostRequestActionsSettingsSubtitle(shortcut: Shortcut): CharSequence =
        getQuantityString(
            shortcut.successActions.size + shortcut.failureActions.size,
            R.string.subtitle_actions_none,
            R.plurals.subtitle_actions_pattern
        )

    private fun getQuantityString(count: Int, @StringRes zeroRes: Int, @PluralsRes pluralRes: Int) =
        if (count == 0) {
            getString(zeroRes)
        } else {
            context.resources.getQuantityString(pluralRes, count, count)
        }

    val variables: ListLiveData<Variable>
        get() = persistedRealm
            .where<Variable>()
            .findAllAsync()
            .toLiveData()

    companion object {

        const val VALIDATION_ERROR_EMPTY_NAME = 1
        const val VALIDATION_ERROR_INVALID_URL = 2

    }

}