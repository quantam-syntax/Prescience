package com.example.consent_cam.privacy.rendering

import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.graphics.RectF
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import com.consentcam.privacy.api.BlurRegion
import com.consentcam.privacy.api.NormalizedRect
import com.example.consent_cam.vision.FaceCoordinateMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * GPU-backed CameraX effect. The fragment shader samples the original camera texture at a coarse
 * grid only inside active face boxes, so protected output is pixelated rather than painted over.
 */
class ProtectedOutputEffect(
    onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val renderThread = HandlerThread("ConsentCamPixelation").also { it.start() }
    private val renderHandler = Handler(renderThread.looper)
    private val renderExecutor = Executor { command -> renderHandler.post(command) }
    private val processor = FacePixelationSurfaceProcessor(renderHandler, renderExecutor, onError)

    val cameraEffect: CameraEffect = PixelationCameraEffect(
        processor = processor,
        executor = renderExecutor,
        onError = onError,
    )

    fun update(protectedFrame: ProtectedFrameState, protectionActive: Boolean) {
        processor.update(active = protectionActive, protectedFrame = protectedFrame)
    }

    fun hasActiveRegions(): Boolean = processor.hasActiveRegions()

    override fun close() {
        processor.close()
        renderThread.quitSafely()
    }
}

private class PixelationCameraEffect(
    processor: SurfaceProcessor,
    executor: Executor,
    onError: (Throwable) -> Unit,
) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE or IMAGE_CAPTURE,
    executor,
    processor,
    Consumer(onError),
)

private class FacePixelationSurfaceProcessor(
    private val handler: Handler,
    private val executor: Executor,
    private val onError: (Throwable) -> Unit,
) : SurfaceProcessor, AutoCloseable {
    private data class State(
        val active: Boolean = false,
        val protectedFrame: ProtectedFrameState = ProtectedFrameState(),
    )

    private data class Output(
        val surfaceOutput: SurfaceOutput,
        val eglSurface: android.opengl.EGLSurface,
    )

    private val state = AtomicReference(State())
    private val outputs = mutableListOf<Output>()
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }
    private val textureBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: android.opengl.EGLConfig? = null
    private var program = 0
    private var textureId = 0
    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputRequest: SurfaceRequest? = null
    private var closed = false

    fun update(active: Boolean, protectedFrame: ProtectedFrameState) {
        state.set(
            State(
                active = active,
                protectedFrame = protectedFrame.copy(
                    regions = protectedFrame.regions.filter(BlurRegion::enabled).take(MAX_FACE_REGIONS),
                ),
            ),
        )
    }

    fun hasActiveRegions(): Boolean = state.get().protectedFrame.regions.isNotEmpty()

    override fun onInputSurface(request: SurfaceRequest) {
        try {
            check(!closed) { "Pixelation processor is closed" }
            ensureGl()
            releaseInput()
            inputRequest = request
            inputTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
                setOnFrameAvailableListener({ drawFrame() }, handler)
            }
            inputSurface = Surface(requireNotNull(inputTexture))
            request.provideSurface(requireNotNull(inputSurface), executor) {
                // CameraX can return the old input after its replacement is bound.
                // That completion must not destroy the new camera's live surface.
                handler.post { if (inputRequest === request) releaseInput() }
            }
        } catch (error: Throwable) {
            reportAndInvalidate(error)
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        try {
            check(!closed) { "Pixelation processor is closed" }
            ensureGl()
            val surface = surfaceOutput.getSurface(executor) {
                handler.post { releaseOutput(surfaceOutput) }
            }
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                requireNotNull(eglConfig),
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "Could not create output EGL surface" }
            outputs += Output(surfaceOutput, eglSurface)
        } catch (error: Throwable) {
            reportAndInvalidate(error)
        }
    }

    override fun close() {
        handler.post {
            if (closed) return@post
            closed = true
            outputs.toList().forEach { releaseOutput(it.surfaceOutput) }
            releaseInput()
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
        }
    }

    private fun drawFrame() {
        if (closed || outputs.isEmpty()) return
        val texture = inputTexture ?: return
        try {
            texture.updateTexImage()
            val textureTransform = FloatArray(16)
            texture.getTransformMatrix(textureTransform)
            val snapshot = state.get()
            outputs.toList().forEach { output ->
                EGL14.eglMakeCurrent(eglDisplay, output.eglSurface, output.eglSurface, eglContext)
                val finalTransform = FloatArray(16)
                output.surfaceOutput.updateTransformMatrix(finalTransform, textureTransform)
                GLES20.glViewport(0, 0, output.surfaceOutput.size.width, output.surfaceOutput.size.height)
                render(finalTransform, snapshot, output.surfaceOutput)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, output.eglSurface, texture.timestamp)
                check(EGL14.eglSwapBuffers(eglDisplay, output.eglSurface)) { "Could not swap protected frame" }
            }
        } catch (error: Throwable) {
            reportAndInvalidate(error)
        }
    }

    private fun render(textureTransform: FloatArray, snapshot: State, surfaceOutput: SurfaceOutput) {
        GLES20.glUseProgram(program)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val textureCoordinate = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(textureCoordinate, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, textureTransform, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uProtectionActive"), if (snapshot.active) 1 else 0)
        val outputRegions = mapRegionsToOutput(snapshot.protectedFrame, surfaceOutput)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFaceCount"), outputRegions.size)
        outputRegions.forEachIndexed { index, region ->
            val rect = region.normalizedRect
            GLES20.glUniform4f(
                GLES20.glGetUniformLocation(program, "uFaceRects[$index]"),
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
            )
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program, "uFaceAngles[$index]"),
                Math.toRadians(region.rotationDegrees.toDouble()).toFloat(),
            )
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCameraTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
    }

    /**
     * ImageAnalysis, Preview, ImageCapture and VideoCapture can have different crop/rotation
     * transforms even within one use-case group. Map the detector's analysis buffer coordinates
     * through the sensor into this exact output buffer instead of treating normalized analysis
     * coordinates as encoder coordinates. This prevents the offset, stationary-video mask seen
     * on iQOO hardware.
     */
    private fun mapRegionsToOutput(
        protectedFrame: ProtectedFrameState,
        surfaceOutput: SurfaceOutput,
    ): List<BlurRegion> {
        val detection = protectedFrame.detectionState
        if (detection == null || !detection.hasOutputTransform) return protectedFrame.regions

        val analysisToSensor = Matrix()
        if (!requireNotNull(detection.sensorToBufferTransform).invert(analysisToSensor)) {
            return protectedFrame.regions
        }
        val analysisToOutput = Matrix(analysisToSensor).apply {
            // CameraX documents this exact composition for ImageAnalysis -> effect output.
            postConcat(surfaceOutput.sensorToBufferTransform)
        }
        val outputWidth = surfaceOutput.size.width.toFloat()
        val outputHeight = surfaceOutput.size.height.toFloat()
        if (outputWidth <= 0f || outputHeight <= 0f) return emptyList()

        return protectedFrame.regions.mapNotNull { region ->
            val analysisRect = FaceCoordinateMapper.denormalizeToBuffer(
                rect = region.normalizedRect,
                orientedCrop = requireNotNull(detection.orientedCrop),
                bufferWidth = detection.bufferWidth,
                bufferHeight = detection.bufferHeight,
                rotationDegrees = detection.rotationDegrees,
                mirrorHorizontally = detection.mirrorHorizontally,
            )
            val bufferRect = RectF(
                analysisRect.left,
                analysisRect.top,
                analysisRect.right,
                analysisRect.bottom,
            )
            analysisToOutput.mapRect(bufferRect)
            val left = (bufferRect.left / outputWidth).coerceIn(0f, 1f)
            val top = (bufferRect.top / outputHeight).coerceIn(0f, 1f)
            val right = (bufferRect.right / outputWidth).coerceIn(0f, 1f)
            val bottom = (bufferRect.bottom / outputHeight).coerceIn(0f, 1f)
            if (right - left <= 0.001f || bottom - top <= 0.001f) null else region.copy(
                normalizedRect = NormalizedRect(left, top, right, bottom),
            )
        }
    }

    private fun ensureGl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        check(EGL14.eglInitialize(eglDisplay, IntArray(1), 0, IntArray(1), 0)) { "Could not initialize EGL" }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(
            eglDisplay,
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            ),
            0,
            configs,
            0,
            1,
            configCount,
            0,
        ) && configCount[0] > 0) { "No EGL config" }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            requireNotNull(eglConfig),
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context" }
        val pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            requireNotNull(eglConfig),
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) { "Could not make EGL context current" }
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        EGL14.eglDestroySurface(eglDisplay, pbuffer)
    }

    private fun releaseInput() {
        inputTexture?.setOnFrameAvailableListener(null)
        inputTexture?.release()
        inputSurface?.release()
        inputTexture = null
        inputSurface = null
        inputRequest = null
    }

    private fun releaseOutput(surfaceOutput: SurfaceOutput) {
        val output = outputs.firstOrNull { it.surfaceOutput == surfaceOutput } ?: return
        outputs.remove(output)
        EGL14.eglDestroySurface(eglDisplay, output.eglSurface)
        surfaceOutput.close()
    }

    private fun reportAndInvalidate(error: Throwable) {
        onError(error)
        inputRequest?.invalidate()
    }

    private fun createProgram(vertexShader: String, fragmentShader: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
    }

    private companion object {
        const val MAX_FACE_REGIONS = 6

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vScreenCoord;
            void main() {
              gl_Position = aPosition;
              vScreenCoord = aTexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uCameraTexture;
            uniform mat4 uTexMatrix;
            uniform int uProtectionActive;
            uniform int uFaceCount;
            uniform vec4 uFaceRects[6];
            uniform float uFaceAngles[6];
            varying vec2 vScreenCoord;
            void main() {
              vec2 faceCoord = vec2(vScreenCoord.x, 1.0 - vScreenCoord.y);
              bool protectedFace = false;
              vec4 protectedRect = vec4(0.0);
              for (int index = 0; index < 6; index++) {
                if (index < uFaceCount) {
                  vec4 face = uFaceRects[index];
                  vec2 center = (face.xy + face.zw) * 0.5;
                  vec2 radius = max((face.zw - face.xy) * 0.5, vec2(0.001));
                  float angle = uFaceAngles[index];
                  float cosine = cos(angle);
                  float sine = sin(angle);
                  vec2 delta = faceCoord - center;
                  vec2 local = vec2(
                    cosine * delta.x + sine * delta.y,
                    -sine * delta.x + cosine * delta.y
                  );
                  vec2 oval = local / radius;
                  if (!protectedFace && dot(oval, oval) <= 1.0) {
                    protectedFace = true;
                    protectedRect = face;
                  }
                }
              }
              vec2 samplingScreenCoord = vScreenCoord;
              if (uProtectionActive == 1 && protectedFace) {
                vec2 faceSize = max(protectedRect.zw - protectedRect.xy, vec2(0.001));
                vec2 block = faceSize / vec2(10.0, 12.0);
                vec2 pixelatedFace = protectedRect.xy +
                  floor((faceCoord - protectedRect.xy) / block) * block + block * 0.5;
                samplingScreenCoord = vec2(pixelatedFace.x, 1.0 - pixelatedFace.y);
              }
              vec2 sampleCoord = (uTexMatrix * vec4(samplingScreenCoord, 0.0, 1.0)).xy;
              gl_FragColor = texture2D(uCameraTexture, sampleCoord);
            }
        """
    }
}
