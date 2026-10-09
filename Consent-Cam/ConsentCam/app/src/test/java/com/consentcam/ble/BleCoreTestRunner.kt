package com.consentcam.ble

import com.consentcam.ble.protocol.ConsentAdvertisementCodecTest
import com.consentcam.ble.proximity.ProximityTrackerTest
import com.consentcam.ble.security.RotatingTokenTest
import com.consentcam.ble.enrollment.EnrollmentCipherTest
import com.consentcam.ble.enrollment.GattChunkTransportTest
import com.consentcam.ble.enrollment.QrSessionSecretCodecTest
import com.consentcam.ble.integration.BleCorePipelineTest
import com.consentcam.ble.session.VerifiedSessionRegistryTest
import com.consentcam.ble.session.DirectBleConsentRegistryTest
import com.consentcam.privacy.decision.ProtectionDecisionCoordinatorTest
import com.consentcam.privacy.region.BlurRegionGeneratorTest
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/** Temporary direct runner for the documentation-only phase before the Gradle wrapper lands. */
object BleCoreTestRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        val testClasses = listOf(
            ConsentAdvertisementCodecTest::class.java,
            RotatingTokenTest::class.java,
            ProximityTrackerTest::class.java,
            VerifiedSessionRegistryTest::class.java,
            DirectBleConsentRegistryTest::class.java,
            EnrollmentCipherTest::class.java,
            GattChunkTransportTest::class.java,
            QrSessionSecretCodecTest::class.java,
            BleCorePipelineTest::class.java,
            ProtectionDecisionCoordinatorTest::class.java,
            BlurRegionGeneratorTest::class.java,
        )
        val failures = mutableListOf<String>()
        var passed = 0

        testClasses.forEach { testClass ->
            val instance = testClass.getDeclaredConstructor().newInstance()
            testClass.declaredMethods
                .filter { it.isAnnotationPresent(Test::class.java) }
                .forEach { method ->
                    try {
                        method.invoke(instance)
                        passed += 1
                    } catch (error: InvocationTargetException) {
                        failures += "${testClass.simpleName}.${method.name}: ${error.targetException}"
                    }
                }
        }

        println("BLE core tests: $passed passed, ${failures.size} failed")
        failures.forEach(::println)
        check(failures.isEmpty())
    }
}
