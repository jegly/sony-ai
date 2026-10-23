# sony-ai

AI effects that run on the Sony A7 II itself. No phone, no computer, no cloud. You take a photo, and a few seconds later the camera saves a copy with the effect.

The main app is called **Ghosts**. It started as one idea (turn the people in a busy street into glowing white figures) and grew from there.

The A7 II runs Android 4.1 on a Cortex-A5 chip with no NEON, so normal AI runtimes like LiteRT won't run on it. This repo has a small engine written in plain C that runs YOLO and MediaPipe models on that chip, plus the app that uses it.

## What it does

Pick an **Effect** in the Fn menu:

| Effect | What you get |
| --- | --- |
| Ghosts | People become glowing white figures. Solid, Ethereal or Dreamy, with adjustable opacity |
| Wireframe | A triangle mesh over each person, with a real face mesh (478 points) on faces |
| Face mesh | Just the face mesh, on every face it finds. Quicker, since it skips the people model |
| Points | A point cloud over people |
| Contours | Scan-line contours that follow the body |
| Skeleton | Neon stick figures from a pose model |
| Thermal | A heat camera: people glow hot, the scene stays cold |
| Glitch | People broken up digitally, colour channels pulled apart |
| Noir pop | People in colour on black and white film |
| Bokeh | The background thrown out of focus, more so further from the subject |
| Rim light | Backlit silhouettes with a glowing rim of light |
| Comic | People as pop art with dots and an ink outline |
| Crowd remover | Take a few shots from a tripod and the people walking through vanish |
| Trails | Same idea, but each shot's people stay as a fading trail of ghosts |

On top of any of those you can add a **Diffusion** filter: Black Mist 1/8 to 1, White Mist 1/4 and 1/2, or Glimmer 1/4. It works on linear light like a real filter, so highlights bloom properly.

Other settings:

- **Target**: People, Vehicles, Animals, People + animals or Everything (any of the 80 COCO classes)
- **Face**: Mesh, Points, or Mesh with glowing eyes
- **Amount, Colour, Glow**: strength, light colour (White, Moonlight, Warm, Gold, Rose, Violet, Cyan, Green) and glow
- **Model**: Fast, Normal or Fine. Bigger finds more people and takes longer
- **Shots**: how many shots go into Crowd remover and Trails
- **Live**: Sony's own Soft focus or Soft high-key, so you can see a soft look in the viewfinder (JPEG only)
- **Soft skin, WB, Kelvin, D-Range**: passed through to the camera

Everything is off until you pick something, so shots are left alone by default.

Copies go to `GHOSTS/` on the card, named after the photo (`DSC05253.JPG` gives `GH05253.JPG`). They keep the original's Exif. Press Play to review them in the app: C1 flips between the copy and the original, and C2 (or the centre button) applies the current effect to the photo on screen, so you can try effects on old shots too.

## Speed

The camera gives apps one slow core, about 190 MFLOPS at best. Rough times per photo on the Normal model:

- Ghosts and most effects: around 30 to 40 seconds
- Skeleton: around 25 to 30 seconds
- Face mesh: around 15 seconds plus a few seconds per face
- Diffusion alone: around 10 to 15 seconds

It all runs in the background, so you can keep shooting. The log in `GHOSTS/LOG.TXT` has exact timings.

## Installing

You need [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE). Set the camera's USB connection to MTP, plug it in, then:

```
python pmca-console.py install -f Ghosts.apk
```

Set Quality to RAW & JPEG (or JPEG), because the effects work on the JPEG. For Live effects Sony needs plain JPEG.

This is a hobby project and installing unofficial apps is at your own risk. It has only been tested on an A7 II with firmware 4.01.

## Building

You need the Android SDK (platform 10, build-tools 36), the NDK, and a stub jar of Sony's camera API built from [OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework).

```
SONY_STUBS=/path/to/sony-stubs.jar app/build.sh
```

The models are already converted and checked in. To make them again you need Python with `ultralytics`, `torch`, `ai-edge-litert` and `tflite`:

```
python tools/lower.py yolo11n-seg.pt model/yolo11n-seg.bin --check photo.jpg
python tools/lower.py yolo11n-pose.pt model/yolo11n-pose.bin --check photo.jpg
python tools/lower_tflite.py face_detector.tflite model/face_detector.bin --scale 2 --offset -1 --check
python tools/lower_tflite.py face_landmarks_detector.tflite model/face_landmarks.bin --scale 1 --offset 0 --check
```

`tools/check_engine.py` runs the C engine on your computer and compares it with PyTorch and Ultralytics. The `tools/src` folder has small Java programs that render each effect on a computer, which is how the looks were tuned.

## How it works

- `engine/` is the inference engine: about 1200 lines of C99 with a 4x4 register-blocked matrix multiply, Winograd for 3x3 convolutions, and the post-processing (boxes, masks, poses). It reads a simple op list.
- `tools/lower.py` flattens a YOLO11 model into that op list, and `tools/lower_tflite.py` converts MediaPipe's face models.
- `app/` is the camera app. The effects are plain Java classes that work on strips of the copy, so a full-size photo never has to fit in the camera's 24 MB of memory.
- `probe/` is the test app used to find out what the camera's chip can do.

## Credits and licence

- YOLO11n-seg and YOLO11n-pose by [Ultralytics](https://github.com/ultralytics/ultralytics), AGPL-3.0
- Face detector, face landmark model and face mesh map by Google [MediaPipe](https://github.com/google-ai-edge/mediapipe), Apache-2.0
- [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) and [OpenMemories](https://github.com/ma1co/OpenMemories-Framework) by ma1co made installing apps on these cameras possible
- The camera code is shared with [sonyalpha-film](https://github.com/jegly/sonyalpha-film)

Because it ships the YOLO models, this project is licensed under the AGPL-3.0. See `LICENSE` and `NOTICE`.
