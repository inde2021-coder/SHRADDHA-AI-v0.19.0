# AKKPS satellite field-boundary model audit

## Findings

1. **India field-boundary training data exists.** The Zenodo record "10,000 Crop Field Boundaries across India" (DOI: 10.5281/zenodo.7315090) contains 10,000 manually delineated field polygons and a FracTAL ResUNet checkpoint fine-tuned on Airbus SPOT imagery in India. The record lists Creative Commons Attribution 4.0 International and a 256.7 MB MXNet 1.6.0 parameter file.
2. **Sensor compatibility is a hard requirement.** That checkpoint was trained on Airbus SPOT imagery. It must not be assumed to work on Sentinel-2, Google Maps tiles, or screenshots without a separately validated sensor adaptation and pixel-to-ground transform.
3. **The India paper's resolution is important.** The paper reports its best India model on 1.5 m Airbus SPOT imagery (median field mIoU 0.85). It reports that 4.8 m PlanetScope imagery reached high average performance mainly for fields larger than 1 hectare. This is not evidence that the checkpoint works on arbitrary low-resolution web-map tiles.
4. **The DECODE source code has a separate license notice.** The Digital Earth Africa repository root contains Apache-2.0, but its nested `decode/README.md` identifies the FracTAL-ResUNet implementation as CSIRO BSD/MIT. Do not assume the root license overrides the nested code notice; preserve and review the relevant source-level license/NOTICE before copying or redistributing model code.
5. **SAM 2 is not an agricultural parcel model.** Meta's SAM 2 code and checkpoints are Apache-2.0, but it is a promptable generic image/video segmentation model. A mask from SAM 2 is not automatically a legally or geometrically correct cadastral field boundary.
6. **Fieldscapes / Fields of the World data are not one blanket license.** Each source collection retains its own license. India labels are listed as CC-BY-4.0 in the dataset catalogue; the exact collection metadata and attribution must be retained.

## Integration decision

- Do not put the 256.7 MB MXNet checkpoint in the Android APK.
- Do not download or execute a checkpoint automatically.
- Keep the GPS edge-walk and manual correction workflow available offline.
- Automatic satellite inference must remain disabled unless the checkpoint checksum, license/attribution, source sensor, georeferencing transform, India validation report, and confidence thresholds are all verified.
- A generic segmentation model may assist a human in drawing a candidate mask, but it cannot silently save or submit a field polygon.
- The Android client must receive a georeferenced polygon (GeoJSON/KML) and show it for user review before AKKPS upload.

## Sources

- India 10,000 field dataset and model weights: https://zenodo.org/records/7315090
- Dataset catalogue and per-country licenses: https://github.com/fieldsoftheworld/ftw-datasets-list
- Meta SAM 2 code/checkpoint license and task description: https://github.com/facebookresearch/sam2
- India field delineation paper and image-resolution results: https://www.mdpi.com/2072-4292/14/22/5738
- DECODE implementation and nested source license notice: https://github.com/digitalearthafrica/FAO_crop_boundary

## Current status

This PR adds a fail-closed eligibility policy, not a trained or deployed segmentation model. Real automatic detection still requires model conversion/inference integration and validation against field-edge ground truth from the target region.
