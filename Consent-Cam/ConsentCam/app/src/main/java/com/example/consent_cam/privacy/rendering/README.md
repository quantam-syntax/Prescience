# Privacy rendering

`ProtectedOutputEffect` is a CameraX GPU `SurfaceProcessor`. It samples the original camera
texture at a coarse grid only inside enabled face regions, so preview, saved photos, and recorded
video use real pixelation rather than an opaque cover. `FacePixelationOverlay` now only shows
unprotected detection verification boxes.

`FaceRegionSmoother` reduces detector jitter, spatially re-associates a face when ML Kit changes
its tracking ID, and holds a region for 450 ms across an isolated missed detection. Turning
protection off clears retained regions immediately.

Face tilt from ML Kit is carried through the privacy contracts, smoothed across frames, and
applied as a rotated oval in the GLES shader. This follows head orientation more tightly while
retaining padded-box fallback coverage for every tracked face. ML Kit contour mode is not used
because it limits useful multi-face tracking to the most prominent face.
