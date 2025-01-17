package abi44_0_0.expo.modules.facedetector

import android.content.Context
import abi44_0_0.expo.modules.core.BasePackage

class FaceDetectorPackage : BasePackage() {
  override fun createInternalModules(context: Context) =
    listOf(ExpoFaceDetectorProvider())

  override fun createExportedModules(context: Context) =
    listOf(FaceDetectorModule(context))
}
