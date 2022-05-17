package expo.modules.kotlin

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContext
import expo.modules.core.interfaces.ActivityProvider
import expo.modules.interfaces.barcodescanner.BarCodeScannerInterface
import expo.modules.interfaces.camera.CameraViewInterface
import expo.modules.interfaces.constants.ConstantsInterface
import expo.modules.interfaces.filesystem.FilePermissionModuleInterface
import expo.modules.interfaces.font.FontManagerInterface
import expo.modules.interfaces.imageloader.ImageLoaderInterface
import expo.modules.interfaces.permissions.Permissions
import expo.modules.interfaces.sensors.SensorServiceInterface
import expo.modules.interfaces.taskManager.TaskManagerInterface
import expo.modules.kotlin.activityresult.ActivityResultsManager
import expo.modules.kotlin.activityresult.AppContextActivityResultCallback
import expo.modules.kotlin.activityresult.AppContextActivityResultCaller
import expo.modules.kotlin.defaultmodules.ErrorManagerModule
import expo.modules.kotlin.events.EventEmitter
import expo.modules.kotlin.events.EventName
import expo.modules.kotlin.events.KEventEmitterWrapper
import expo.modules.kotlin.events.KModuleEventEmitterWrapper
import expo.modules.kotlin.events.OnActivityResultPayload
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.providers.AppCompatActivityProvider
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AppContext(
  modulesProvider: ModulesProvider,
  val legacyModuleRegistry: expo.modules.core.ModuleRegistry,
  private val reactContextHolder: WeakReference<ReactApplicationContext>
):
  AppCompatActivityProvider,
  AppContextActivityResultCaller
{
  val registry = ModuleRegistry(WeakReference(this)).apply {
    register(ErrorManagerModule())
    register(modulesProvider)
  }
  private val reactLifecycleDelegate = ReactLifecycleDelegate(this)
  private val activityResultsManager = ActivityResultsManager(this)

  init {
    requireNotNull(reactContextHolder.get()) {
      "The app context should be created with valid react context."
    }.apply {
      addLifecycleEventListener(reactLifecycleDelegate)
      addActivityEventListener(reactLifecycleDelegate)
    }
  }

  /**
   * Returns a legacy module implementing given interface.
   */
  inline fun <reified Module> legacyModule(): Module? {
    return try {
      legacyModuleRegistry.getModule(Module::class.java)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Provides access to app's constants from the legacy module registry.
   */
  val constants: ConstantsInterface?
    get() = legacyModule()

  /**
   * Provides access to the file system manager from the legacy module registry.
   */
  val filePermission: FilePermissionModuleInterface?
    get() = legacyModule()

  /**
   * Provides access to the permissions manager from the legacy module registry
   */
  val permissions: Permissions?
    get() = legacyModule()

  /**
   * Provides access to the image loader from the legacy module registry
   */
  val imageLoader: ImageLoaderInterface?
    get() = legacyModule()

  /**
   * Provides access to the bar code scanner manager from the legacy module registry
   */
  val barcodeScanner: BarCodeScannerInterface?
    get() = legacyModule()

  /**
   * Provides access to the camera view manager from the legacy module registry
   */
  val camera: CameraViewInterface?
    get() = legacyModule()

  /**
   * Provides access to the font manager from the legacy module registry
   */
  val font: FontManagerInterface?
    get() = legacyModule()

  /**
   * Provides access to the sensor manager from the legacy module registry
   */
  val sensor: SensorServiceInterface?
    get() = legacyModule()

  /**
   * Provides access to the task manager from the legacy module registry
   */
  val taskManager: TaskManagerInterface?
    get() = legacyModule()

  /**
   * Provides access to the activity provider from the legacy module registry
   */
  @Deprecated(
    message = "This provider enables accessing generic Activity that is an instance of ReactActivity " +
      "that subclasses AppCompatActivity. Access AppCompatActivity directly via currentAppCompatActivity.",
    replaceWith = ReplaceWith("currentAppCompatActivity")
  )
  val activityProvider: ActivityProvider?
    get() = legacyModule()

  /**
   * Provides access to the react application context
   */
  val reactContext: ReactContext?
    get() = reactContextHolder.get()

  /**
   * Provides access to the event emitter
   */
  fun eventEmitter(module: Module): EventEmitter? {
    val legacyEventEmitter = legacyModule<expo.modules.core.interfaces.services.EventEmitter>()
      ?: return null
    return KModuleEventEmitterWrapper(
      requireNotNull(registry.getModuleHolder(module)) {
        "Cannot create an event emitter for the module that isn't present in the module registry."
      },
      legacyEventEmitter,
      reactContextHolder
    )
  }

  internal val callbackInvoker: EventEmitter?
    get() {
      val legacyEventEmitter = legacyModule<expo.modules.core.interfaces.services.EventEmitter>()
        ?: return null
      return KEventEmitterWrapper(legacyEventEmitter, reactContextHolder)
    }

  internal val errorManager: ErrorManagerModule?
    get() = registry.getModule()

  fun onDestroy() {
    reactContextHolder.get()?.removeLifecycleEventListener(reactLifecycleDelegate)
    registry.post(EventName.MODULE_DESTROY)
    registry.cleanUp()
  }

  fun onHostResume() {
    registry.post(EventName.ACTIVITY_ENTERS_FOREGROUND)
  }

  fun onHostPause() {
    registry.post(EventName.ACTIVITY_ENTERS_BACKGROUND)
  }

  fun onHostDestroy() {
    registry.post(EventName.ACTIVITY_DESTROYS)
  }

  fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
    activityResultsManager.onActivityResult(activity, requestCode, resultCode, data)
    registry.post(
      EventName.ON_ACTIVITY_RESULT,
      activity,
      OnActivityResultPayload(
        requestCode,
        resultCode,
        data
      )
    )
  }

  fun onNewIntent(intent: Intent?) {
    registry.post(
      EventName.ON_NEW_INTENT,
      intent
    )
  }

// region AppCompatActivityProvider

  override val appCompatActivity: AppCompatActivity?
    get() {
      val currentActivity = this.activityProvider?.currentActivity ?: return null

      if (currentActivity !is AppCompatActivity) {
        // TODO(@bbarthec): what happens here? It should rather never happen
        TODO()
      }

      return currentActivity
    }

// endregion

// region AppContextActivityResultCaller

  @MainThread
  override fun <I, O> registerForActivityResult(
    contract: ActivityResultContract<I, O>,
    callback: AppContextActivityResultCallback<O>
  ): ActivityResultLauncher<I> {
    return activityResultsManager.registerForActivityResult(
      contract,
      callback
    )
  }

  @MainThread
  suspend fun <O> launchForActivityResult(
    contract: ActivityResultContract<Any?, O>
  ) = suspendCoroutine<AppContextActivityResult<O>> { continuation ->
    activityResultsManager.registerForActivityResult(
      contract
    ) { output, launchingActivityHasBeenKilled ->
      continuation.resume(AppContextActivityResult(output, launchingActivityHasBeenKilled))
    }.launch(null)
  }

// endregion
}

data class AppContextActivityResult<O>(
  val result: O,
  val launchingActivityHasBeenKilled: Boolean
)
