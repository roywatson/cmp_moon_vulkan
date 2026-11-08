# NASA lunar asset notice

This repository redistributes the following files unchanged:

| File | Source | SHA-256 |
|---|---|---|
| `shared/src/commonMain/composeResources/drawable/lroc_color_2k.jpg` | [NASA CGI Moon Kit](https://svs.gsfc.nasa.gov/4720/) | `f7130a1822681fa7512d7dcfd40db8c10b9ba4f06777910348698260ed7a2170` |

The renderer's high-resolution maps are converted (not byte-identical) from NASA CGI Moon Kit
downloads made on 2026-11-06:

| File | Source file (SHA-256) | Conversion | SHA-256 |
|---|---|---|---|
| `shared/src/commonMain/composeResources/files/moon/lroc_color_4k.jpg` | [`lroc_color_poles_4k.tif`](https://svs.gsfc.nasa.gov/4720/) (`918649a7f8ed2f1329b2cd95bb0d25483befdcb60ae1a66db681a637cc21344f`) | TIFF → JPEG, quality 90, 4096×2048 | `32d2f30822dd7fac0584d13f03f039a6914061449ee0342dc1958a81be1801cf` |
| `shared/src/commonMain/composeResources/files/moon/ldem_16_8bit.jpg` | [`ldem_16.tif`](https://svs.gsfc.nasa.gov/4720/) (`1ea42bf44f7e9d694f79c3afa7145f97fbf06cc67372067d9fe73dce43bad796`) | float32 km, linearly normalised −8.982…10.686 km → 0…255, JPEG quality 92, 5760×2880 | `5f5ca99c95ebd909a1fee53e1d67b899e673772e1bd7d4b843a03b61192939f4` |

Credit this item to **NASA's Goddard Space Flight Center**. NASA SVS identifies its source as Lunar Reconnaissance Orbiter imagery and topographic data. Do not remove this notice, the README attribution, or the attribution in the companion article when publishing this project or derived screenshots.
