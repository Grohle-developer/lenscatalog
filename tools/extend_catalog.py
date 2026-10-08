#!/usr/bin/env python3
"""Appends extra lenses to app/assets/lenses.json (additive, idempotent).

Only factual specs (brand, model, mount, focal length, max aperture) typed
from public manufacturer data. Existing entries are never touched: a lens
whose (brand, model, mount) already exists is skipped.

    python3 tools/extend_catalog.py            # rewrites app/assets/lenses.json
"""
import json, re, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(ROOT, "app", "assets", "lenses.json")

# Brand | Model | Mounts (comma separated) | focal "50" or "24-70" | max aperture
DATA = """
# ---- Canon FD / FL -------------------------------------------------------
Canon|FD 17mm f/4 S.S.C.|FD|17|4
Canon|FD 20mm f/2.8 S.S.C.|FD|20|2.8
Canon|FD 24mm f/2.8 S.S.C.|FD|24|2.8
Canon|FD 28mm f/2 S.S.C.|FD|28|2
Canon|FD 35mm f/2 S.S.C.|FD|35|2
Canon|FD 50mm f/1.2L|FD|50|1.2
Canon|FD 50mm f/1.4 S.S.C.|FD|50|1.4
Canon|FD 50mm f/1.8|FD|50|1.8
Canon|FD 55mm f/1.2 S.S.C.|FD|55|1.2
Canon|FD 85mm f/1.2L|FD|85|1.2
Canon|FD 85mm f/1.8 S.S.C.|FD|85|1.8
Canon|FD 100mm f/2 S.S.C.|FD|100|2
Canon|FD 135mm f/2.5 S.S.C.|FD|135|2.5
Canon|FD 200mm f/4 S.S.C.|FD|200|4
Canon|FD 300mm f/5.6 S.S.C.|FD|300|5.6
Canon|FD 24-35mm f/3.5L|FD|24-35|3.5
Canon|FD 35-105mm f/3.5|FD|35-105|3.5
Canon|FD 80-200mm f/4 S.S.C.|FD|80-200|4
Canon|New FD 50mm f/1.4|FD|50|1.4
Canon|New FD 28mm f/2.8|FD|28|2.8
Canon|New FD 135mm f/2.8|FD|135|2.8
Canon|New FD 70-210mm f/4|FD|70-210|4
Canon|FL 50mm f/1.8|FD|50|1.8
Canon|FL 58mm f/1.2|FD|58|1.2
# ---- Canon EF ------------------------------------------------------------
Canon|EF 24mm f/1.4L II USM|EF|24|1.4
Canon|EF 24mm f/2.8 IS USM|EF|24|2.8
Canon|EF 28mm f/1.8 USM|EF|28|1.8
Canon|EF 35mm f/1.4L USM|EF|35|1.4
Canon|EF 35mm f/1.4L II USM|EF|35|1.4
Canon|EF 35mm f/2 IS USM|EF|35|2
Canon|EF 40mm f/2.8 STM|EF|40|2.8
Canon|EF 50mm f/1.2L USM|EF|50|1.2
Canon|EF 50mm f/1.4 USM|EF|50|1.4
Canon|EF 50mm f/1.8 II|EF|50|1.8
Canon|EF 50mm f/1.8 STM|EF|50|1.8
Canon|EF 85mm f/1.2L II USM|EF|85|1.2
Canon|EF 85mm f/1.8 USM|EF|85|1.8
Canon|EF 135mm f/2L USM|EF|135|2
Canon|EF 200mm f/2.8L II USM|EF|200|2.8
Canon|EF 300mm f/4L IS USM|EF|300|4
Canon|EF 400mm f/5.6L USM|EF|400|5.6
Canon|EF 70-200mm f/2.8L IS II USM|EF|70-200|2.8
Canon|EF 70-200mm f/4L IS USM|EF|70-200|4
Canon|EF 70-200mm f/4L USM|EF|70-200|4
Canon|EF 24-70mm f/2.8L II USM|EF|24-70|2.8
Canon|EF 24-70mm f/4L IS USM|EF|24-70|4
Canon|EF 24-105mm f/4L IS USM|EF|24-105|4
Canon|EF 17-40mm f/4L USM|EF|17-40|4
Canon|EF 16-35mm f/4L IS USM|EF|16-35|4
Canon|EF 28-135mm f/3.5-5.6 IS USM|EF|28-135|3.5
Canon|EF 75-300mm f/4-5.6 III|EF|75-300|4
Canon|EF 70-300mm f/4-5.6 IS USM|EF|70-300|4
Canon|EF 70-300mm f/4-5.6L IS USM|EF|70-300|4
Canon|EF-S 10-18mm f/4.5-5.6 IS STM|EF|10-18|4.5
Canon|EF-S 17-55mm f/2.8 IS USM|EF|17-55|2.8
Canon|EF-S 18-55mm f/3.5-5.6 IS STM|EF|18-55|3.5
Canon|EF-S 24mm f/2.8 STM|EF|24|2.8
Canon|EF-S 60mm f/2.8 Macro USM|EF|60|2.8
Canon|MP-E 65mm f/2.8 1-5x Macro|EF|65|2.8
Canon|TS-E 24mm f/3.5L II|EF|24|3.5
Canon|TS-E 45mm f/2.8|EF|45|2.8
Canon|TS-E 90mm f/2.8|EF|90|2.8
# ---- Nikon F -------------------------------------------------------------
Nikon|Nikkor-S Auto 35mm f/2.8|F|35|2.8
Nikon|Nikkor-H Auto 28mm f/3.5|F|28|3.5
Nikon|Nikkor-N Auto 24mm f/2.8|F|24|2.8
Nikon|Nikkor-S Auto 50mm f/1.4|F|50|1.4
Nikon|Nikkor-S Auto 55mm f/1.2|F|55|1.2
Nikon|Nikkor-P Auto 105mm f/2.5|F|105|2.5
Nikon|Nikkor-Q Auto 135mm f/2.8|F|135|2.8
Nikon|Nikkor-Q Auto 200mm f/4|F|200|4
Nikon|Nikkor-H Auto 85mm f/1.8|F|85|1.8
Nikon|Ai Nikkor 20mm f/3.5|F|20|3.5
Nikon|Ai Nikkor 24mm f/2.8|F|24|2.8
Nikon|Ai Nikkor 28mm f/2.8|F|28|2.8
Nikon|Ai Nikkor 28mm f/3.5|F|28|3.5
Nikon|Ai Nikkor 35mm f/2|F|35|2
Nikon|Ai Nikkor 50mm f/1.4|F|50|1.4
Nikon|Ai Nikkor 50mm f/1.8|F|50|1.8
Nikon|Ai Nikkor 85mm f/2|F|85|2
Nikon|Ai Nikkor 105mm f/2.5|F|105|2.5
Nikon|Ai Nikkor 135mm f/2.8|F|135|2.8
Nikon|Ai Nikkor 180mm f/2.8 ED|F|180|2.8
Nikon|Ai Nikkor 200mm f/4|F|200|4
Nikon|Ai-S Nikkor 24mm f/2|F|24|2
Nikon|Ai-S Nikkor 28mm f/2|F|28|2
Nikon|Ai-S Nikkor 28mm f/2.8|F|28|2.8
Nikon|Ai-S Nikkor 35mm f/1.4|F|35|1.4
Nikon|Ai-S Nikkor 35mm f/2.8|F|35|2.8
Nikon|Ai-S Nikkor 50mm f/1.2|F|50|1.2
Nikon|Ai-S Nikkor 50mm f/1.4|F|50|1.4
Nikon|Ai-S Nikkor 50mm f/1.8|F|50|1.8
Nikon|Ai-S Nikkor 85mm f/1.4|F|85|1.4
Nikon|Ai-S Nikkor 105mm f/1.8|F|105|1.8
Nikon|Ai-S Nikkor 105mm f/2.5|F|105|2.5
Nikon|Ai-S Nikkor 135mm f/2|F|135|2
Nikon|Ai-S Nikkor 180mm f/2.8 ED|F|180|2.8
Nikon|Ai-S Nikkor 300mm f/4.5 ED|F|300|4.5
Nikon|Ai-S Micro-Nikkor 55mm f/2.8|F|55|2.8
Nikon|Ai-S Micro-Nikkor 105mm f/4|F|105|4
Nikon|Ai-S Zoom-Nikkor 35-105mm f/3.5-4.5|F|35-105|3.5
Nikon|Ai-S Zoom-Nikkor 80-200mm f/4|F|80-200|4
Nikon|Series E 28mm f/2.8|F|28|2.8
Nikon|Series E 35mm f/2.5|F|35|2.5
Nikon|Series E 50mm f/1.8|F|50|1.8
Nikon|Series E 100mm f/2.8|F|100|2.8
Nikon|Series E 135mm f/2.8|F|135|2.8
Nikon|Series E 75-150mm f/3.5|F|75-150|3.5
Nikon|AF-S Nikkor 14-24mm f/2.8G ED|F|14-24|2.8
Nikon|AF-S Nikkor 16-35mm f/4G ED VR|F|16-35|4
Nikon|AF-S Nikkor 24-70mm f/2.8G ED|F|24-70|2.8
Nikon|AF-S Nikkor 24-120mm f/4G ED VR|F|24-120|4
Nikon|AF-S Nikkor 70-200mm f/2.8G ED VR II|F|70-200|2.8
Nikon|AF-S Nikkor 70-200mm f/4G ED VR|F|70-200|4
Nikon|AF-S Nikkor 28-300mm f/3.5-5.6G ED VR|F|28-300|3.5
Nikon|AF-S Nikkor 24mm f/1.4G ED|F|24|1.4
Nikon|AF-S Nikkor 35mm f/1.4G|F|35|1.4
Nikon|AF-S Nikkor 50mm f/1.4G|F|50|1.4
Nikon|AF-S Nikkor 50mm f/1.8G|F|50|1.8
Nikon|AF-S Nikkor 85mm f/1.4G|F|85|1.4
Nikon|AF-S Nikkor 85mm f/1.8G|F|85|1.8
Nikon|AF-S Nikkor 105mm f/1.4E ED|F|105|1.4
Nikon|AF-S Nikkor 300mm f/4E PF ED VR|F|300|4
Nikon|AF-S DX Nikkor 10-24mm f/3.5-4.5G ED|F|10-24|3.5
Nikon|AF-S DX Nikkor 12-24mm f/4G IF-ED|F|12-24|4
Nikon|AF-S DX Nikkor 16-85mm f/3.5-5.6G ED VR|F|16-85|3.5
Nikon|AF-S DX Nikkor 17-55mm f/2.8G IF-ED|F|17-55|2.8
Nikon|AF-S DX Nikkor 18-55mm f/3.5-5.6G VR|F|18-55|3.5
Nikon|AF-S DX Nikkor 18-105mm f/3.5-5.6G ED VR|F|18-105|3.5
Nikon|AF-S DX Nikkor 18-140mm f/3.5-5.6G ED VR|F|18-140|3.5
Nikon|AF-S DX Nikkor 18-200mm f/3.5-5.6G ED VR II|F|18-200|3.5
Nikon|AF-S DX Nikkor 35mm f/1.8G|F|35|1.8
Nikon|AF-S DX Micro Nikkor 40mm f/2.8G|F|40|2.8
Nikon|AF-S DX Micro Nikkor 85mm f/3.5G ED VR|F|85|3.5
Nikon|AF-S Micro Nikkor 60mm f/2.8G ED|F|60|2.8
Nikon|AF-S VR Micro-Nikkor 105mm f/2.8G IF-ED|F|105|2.8
Nikon|PC-E Nikkor 24mm f/3.5D ED|F|24|3.5
Nikon|PC-E Micro Nikkor 45mm f/2.8D ED|F|45|2.8
Nikon|PC-E Micro Nikkor 85mm f/2.8D|F|85|2.8
Nikon|Reflex-Nikkor 500mm f/8|F|500|8
Nikon|Reflex-Nikkor 1000mm f/11|F|1000|11
Nikon|Fisheye-Nikkor 8mm f/2.8|F|8|2.8
Nikon|Fisheye-Nikkor 16mm f/2.8|F|16|2.8
# ---- Pentax / Takumar (K and M42) ----------------------------------------
Pentax|Super-Takumar 28mm f/3.5|M42|28|3.5
Pentax|Super-Takumar 35mm f/3.5|M42|35|3.5
Pentax|Super-Takumar 50mm f/1.4|M42|50|1.4
Pentax|Super-Takumar 50mm f/1.8|M42|50|1.8
Pentax|Super-Takumar 55mm f/2|M42|55|2
Pentax|Super-Takumar 85mm f/1.9|M42|85|1.9
Pentax|Super-Takumar 105mm f/2.8|M42|105|2.8
Pentax|Super-Takumar 135mm f/3.5|M42|135|3.5
Pentax|Super-Takumar 200mm f/4|M42|200|4
Pentax|Super-Takumar 300mm f/4|M42|300|4
Pentax|Super-Multi-Coated Takumar 24mm f/3.5|M42|24|3.5
Pentax|Super-Multi-Coated Takumar 28mm f/3.5|M42|28|3.5
Pentax|Super-Multi-Coated Takumar 35mm f/2|M42|35|2
Pentax|Super-Multi-Coated Takumar 50mm f/1.4|M42|50|1.4
Pentax|Super-Multi-Coated Takumar 50mm f/4 Macro|M42|50|4
Pentax|Super-Multi-Coated Takumar 55mm f/1.8|M42|55|1.8
Pentax|Super-Multi-Coated Takumar 85mm f/1.8|M42|85|1.8
Pentax|Super-Multi-Coated Takumar 105mm f/2.8|M42|105|2.8
Pentax|Super-Multi-Coated Takumar 135mm f/3.5|M42|135|3.5
Pentax|Super-Multi-Coated Takumar 150mm f/4|M42|150|4
Pentax|Super-Multi-Coated Takumar 200mm f/4|M42|200|4
Pentax|Super-Multi-Coated Takumar 300mm f/4|M42|300|4
Pentax|SMC Takumar 50mm f/1.4|M42|50|1.4
Pentax|SMC Takumar 55mm f/1.8|M42|55|1.8
Pentax|SMC Pentax 15mm f/3.5|K|15|3.5
Pentax|SMC Pentax 20mm f/4|K|20|4
Pentax|SMC Pentax-M 28mm f/2.8|K|28|2.8
Pentax|SMC Pentax-M 28mm f/3.5|K|28|3.5
Pentax|SMC Pentax-M 35mm f/2|K|35|2
Pentax|SMC Pentax-M 35mm f/2.8|K|35|2.8
Pentax|SMC Pentax-M 50mm f/1.4|K|50|1.4
Pentax|SMC Pentax-M 50mm f/1.7|K|50|1.7
Pentax|SMC Pentax-M 50mm f/2|K|50|2
Pentax|SMC Pentax-M 85mm f/2|K|85|2
Pentax|SMC Pentax-M 100mm f/2.8|K|100|2.8
Pentax|SMC Pentax-M 135mm f/3.5|K|135|3.5
Pentax|SMC Pentax-M 200mm f/4|K|200|4
Pentax|SMC Pentax-A 35mm f/2.8|K|35|2.8
Pentax|SMC Pentax-A 50mm f/1.2|K|50|1.2
Pentax|SMC Pentax-A 85mm f/1.4|K|85|1.4
Pentax|SMC Pentax-A 135mm f/2.8|K|135|2.8
Pentax|SMC Pentax-A 70-210mm f/4|K|70-210|4
Pentax|SMC Pentax-F 28mm f/2.8|K|28|2.8
Pentax|SMC Pentax-FA 31mm f/1.8 AL Limited|K|31|1.8
Pentax|SMC Pentax-FA 43mm f/1.9 Limited|K|43|1.9
Pentax|SMC Pentax-FA 77mm f/1.8 Limited|K|77|1.8
Pentax|SMC Pentax-FA 50mm f/1.4|K|50|1.4
Pentax|SMC Pentax-FA 28-70mm f/4 AL|K|28-70|4
Pentax|SMC Pentax-FA* 24mm f/2 AL IF|K|24|2
Pentax|SMC Pentax-FA* 85mm f/1.4 IF|K|85|1.4
Pentax|SMC Pentax-DA 35mm f/2.4 AL|K|35|2.4
Pentax|SMC Pentax-DA 40mm f/2.8 Limited|K|40|2.8
Pentax|SMC Pentax-DA 50mm f/1.8|K|50|1.8
Pentax|SMC Pentax-DA 70mm f/2.4 Limited|K|70|2.4
Pentax|SMC Pentax-DA* 16-50mm f/2.8 ED AL IF SDM|K|16-50|2.8
Pentax|SMC Pentax-DA* 50-135mm f/2.8 ED IF SDM|K|50-135|2.8
Pentax|SMC Pentax-DA* 300mm f/4 ED IF SDM|K|300|4
Pentax|SMC Pentax-D FA 50mm f/2.8 Macro|K|50|2.8
Pentax|SMC Pentax-D FA 100mm f/2.8 Macro WR|K|100|2.8
Pentax|HD Pentax-D FA 15-30mm f/2.8 ED SDM WR|K|15-30|2.8
Pentax|HD Pentax-D FA 24-70mm f/2.8 ED SDM WR|K|24-70|2.8
Pentax|HD Pentax-D FA 70-200mm f/2.8 ED DC AW|K|70-200|2.8
Pentax|HD Pentax-D FA 28-105mm f/3.5-5.6 ED DC WR|K|28-105|3.5
# ---- Minolta MC / MD / Rokkor + A mount ----------------------------------
Minolta|MC Rokkor-PF 55mm f/1.7|MD|55|1.7
Minolta|MC Rokkor-PF 58mm f/1.4|MD|58|1.4
Minolta|MC Rokkor-X 50mm f/1.4|MD|50|1.4
Minolta|MC Rokkor-X 135mm f/3.5|MD|135|3.5
Minolta|MC W.Rokkor-HG 28mm f/2.8|MD|28|2.8
Minolta|MC Rokkor-PG 58mm f/1.2|MD|58|1.2
Minolta|MD Rokkor-X 17mm f/4|MD|17|4
Minolta|MD Rokkor-X 20mm f/2.8|MD|20|2.8
Minolta|MD Rokkor-X 28mm f/2|MD|28|2
Minolta|MD Rokkor-X 35mm f/1.8|MD|35|1.8
Minolta|MD Rokkor-X 50mm f/1.7|MD|50|1.7
Minolta|MD Rokkor-X 50mm f/3.5 Macro|MD|50|3.5
Minolta|MD Rokkor-X 85mm f/2|MD|85|2
Minolta|MD Rokkor-X 100mm f/2.5|MD|100|2.5
Minolta|MD Rokkor-X 135mm f/2.8|MD|135|2.8
Minolta|MD Rokkor-X 200mm f/3.5|MD|200|3.5
Minolta|MD Rokkor-X 300mm f/4.5|MD|300|4.5
Minolta|MD Zoom 35-70mm f/3.5|MD|35-70|3.5
Minolta|MD Zoom 28-85mm f/3.5-4.5|MD|28-85|3.5
Minolta|MD Zoom 70-210mm f/4|MD|70-210|4
Minolta|AF 20mm f/2.8|A|20|2.8
Minolta|AF 24mm f/2.8|A|24|2.8
Minolta|AF 28mm f/2|A|28|2
Minolta|AF 28mm f/2.8|A|28|2.8
Minolta|AF 35mm f/1.4 G|A|35|1.4
Minolta|AF 35mm f/2|A|35|2
Minolta|AF 100mm f/2|A|100|2
Minolta|AF 100mm f/2.8 Macro|A|100|2.8
Minolta|AF 200mm f/2.8 APO G|A|200|2.8
Minolta|AF 300mm f/2.8 APO G|A|300|2.8
Minolta|AF 70-200mm f/2.8 APO G|A|70-200|2.8
Minolta|AF 80-200mm f/2.8 APO|A|80-200|2.8
Minolta|AF 28-135mm f/4-4.5|A|28-135|4
Minolta|AF 24-85mm f/3.5-4.5|A|24-85|3.5
# ---- Olympus OM Zuiko ----------------------------------------------------
Olympus|OM-System G.Zuiko Auto-W 28mm f/3.5|OM|28|3.5
Olympus|OM-System G.Zuiko Auto-S 40mm f/2|OM|40|2
Olympus|OM-System G.Zuiko Auto-S 50mm f/1.4|OM|50|1.4
Olympus|OM-System F.Zuiko Auto-S 50mm f/1.8|OM|50|1.8
Olympus|OM-System F.Zuiko Auto-S 50mm f/1.2|OM|50|1.2
Olympus|OM-System Zuiko Auto-S 50mm f/3.5 Macro|OM|50|3.5
Olympus|OM-System Zuiko Auto-W 18mm f/3.5|OM|18|3.5
Olympus|OM-System Zuiko Auto-W 21mm f/2|OM|21|2
Olympus|OM-System Zuiko Auto-W 21mm f/3.5|OM|21|3.5
Olympus|OM-System Zuiko Auto-W 24mm f/2|OM|24|2
Olympus|OM-System Zuiko Auto-W 24mm f/2.8|OM|24|2.8
Olympus|OM-System Zuiko Auto-W 28mm f/2|OM|28|2
Olympus|OM-System Zuiko Auto-W 28mm f/2.8|OM|28|2.8
Olympus|OM-System Zuiko Auto-W 35mm f/2|OM|35|2
Olympus|OM-System Zuiko Auto-W 35mm f/2.8|OM|35|2.8
Olympus|OM-System Zuiko Auto-T 85mm f/2|OM|85|2
Olympus|OM-System Zuiko Auto-T 100mm f/2|OM|100|2
Olympus|OM-System Zuiko Auto-T 100mm f/2.8|OM|100|2.8
Olympus|OM-System Zuiko Auto-T 135mm f/2.8|OM|135|2.8
Olympus|OM-System Zuiko Auto-T 135mm f/3.5|OM|135|3.5
Olympus|OM-System Zuiko Auto-T 180mm f/2.8|OM|180|2.8
Olympus|OM-System Zuiko Auto-T 200mm f/4|OM|200|4
Olympus|OM-System Zuiko Auto-T 300mm f/4.5|OM|300|4.5
Olympus|OM-System Zuiko Auto-Zoom 35-70mm f/3.6|OM|35-70|3.6
Olympus|OM-System Zuiko Auto-Zoom 75-150mm f/4|OM|75-150|4
Olympus|OM-System Zuiko Auto-Zoom 28-48mm f/4|OM|28-48|4
Olympus|OM-System Zuiko Macro 90mm f/2|OM|90|2
Olympus|OM-System Zuiko Shift 24mm f/3.5|OM|24|3.5
# ---- Leica M and compatible M-mount (Zeiss ZM, Voigtlander VM, etc.) -----
Leica|Summilux-M 35mm f/1.4 Asph.|M|35|1.4
Leica|Summilux-M 50mm f/1.4 Asph.|M|50|1.4
Leica|Summilux-M 75mm f/1.4|M|75|1.4
Leica|Summilux-M 90mm f/1.5 Asph.|M|90|1.5
Leica|Summicron-M 90mm f/2 Asph.|M|90|2
Leica|Summicron-M 21mm f/3.4 Asph.|M|21|3.4
Leica|Summaron-M 28mm f/5.6|M|28|5.6
Leica|Elmar-M 50mm f/2.8|M|50|2.8
Leica|Elmarit-M 21mm f/2.8 Asph.|M|21|2.8
Leica|Elmarit-M 24mm f/2.8 Asph.|M|24|2.8
Leica|Tri-Elmar-M 16-18-21mm f/4 Asph.|M|16-21|4
Leica|Noctilux-M 50mm f/0.95 Asph.|M|50|0.95
Leica|Noctilux-M 50mm f/1|M|50|1
Leica|Tele-Elmarit-M 90mm f/2.8|M|90|2.8
Leica|Hektor 73mm f/1.9|M|73|1.9
Leica|Summicron 50mm f/2 Collapsible|M|50|2
Leica|Summitar 50mm f/2|M39|50|2
Leica|Elmar 50mm f/3.5|M39|50|3.5
Leica|Summaron 35mm f/3.5|M39|35|3.5
Leica|Summarit 50mm f/1.5|M39|50|1.5
Leica|Summicron-R 50mm f/2|R|50|2
Leica|Summilux-R 50mm f/1.4|R|50|1.4
Leica|Elmarit-R 35mm f/2.8|R|35|2.8
Leica|Summicron-R 90mm f/2|R|90|2
Leica|Elmarit-R 180mm f/2.8|R|180|2.8
Leica|Vario-Elmar-R 70-210mm f/4|R|70-210|4
Voigtländer|Nokton Classic 35mm f/1.4 SC|M|35|1.4
Voigtländer|Nokton 40mm f/1.4 SC|M|40|1.4
Voigtländer|Nokton 50mm f/1.5 Aspherical|M|50|1.5
Voigtländer|Nokton 35mm f/1.2 Aspherical III|M|35|1.2
Voigtländer|Nokton 21mm f/1.4 Aspherical|M|21|1.4
Voigtländer|Ultron 35mm f/1.7 Aspherical|M|35|1.7
Voigtländer|Ultron 28mm f/1.9 Aspherical|M|28|1.9
Voigtländer|Color-Skopar 21mm f/4|M|21|4
Voigtländer|Color-Skopar 28mm f/3.5|M|28|3.5
Voigtländer|Color-Skopar 35mm f/2.5 PII|M|35|2.5
Voigtländer|Heliar 75mm f/1.8|M|75|1.8
Voigtländer|Super Wide-Heliar 15mm f/4.5 III|M|15|4.5
Voigtländer|Ultra Wide-Heliar 12mm f/5.6 III|M|12|5.6
Voigtländer|Apo-Lanthar 90mm f/3.5|M|90|3.5
Zeiss|C Biogon T* 21mm f/4.5 ZM|M|21|4.5
Zeiss|Biogon T* 25mm f/2.8 ZM|M|25|2.8
Zeiss|Biogon T* 28mm f/2.8 ZM|M|28|2.8
Zeiss|Biogon T* 35mm f/2 ZM|M|35|2
Zeiss|C Sonnar T* 50mm f/1.5 ZM|M|50|1.5
Zeiss|Planar T* 50mm f/2 ZM|M|50|2
Zeiss|Sonnar T* 85mm f/2 ZM|M|85|2
Zeiss|Distagon T* 15mm f/2.8 ZE|EF|15|2.8
Zeiss|Distagon T* 25mm f/2 ZE|EF|25|2
Zeiss|Distagon T* 28mm f/2 ZE|EF|28|2
Zeiss|Distagon T* 35mm f/1.4 ZE|EF|35|1.4
Zeiss|Planar T* 85mm f/1.4 ZE|EF|85|1.4
Zeiss|Planar T* 50mm f/1.4 ZK|K|50|1.4
Zeiss|Makro-Planar T* 50mm f/2 ZF.2|F|50|2
Zeiss|Apo Sonnar T* 135mm f/2 ZF.2|F|135|2
Zeiss|Otus 55mm f/1.4 Apo Distagon T*|F|55|1.4
Zeiss|Otus 85mm f/1.4 Apo Planar T*|F|85|1.4
Zeiss|Otus 28mm f/1.4 Distagon T*|F|28|1.4
Zeiss|Milvus 21mm f/2.8|F|21|2.8
Zeiss|Milvus 35mm f/2|F|35|2
Zeiss|Milvus 50mm f/1.4|F|50|1.4
Zeiss|Milvus 85mm f/1.4|F|85|1.4
Zeiss|Milvus 100mm f/2 Makro|F|100|2
Zeiss|Planar T* 50mm f/1.7 AE|C/Y|50|1.7
Zeiss|Planar T* 50mm f/1.4 AE|C/Y|50|1.4
Zeiss|Planar T* 85mm f/1.4 AE|C/Y|85|1.4
Zeiss|Distagon T* 35mm f/2.8 AE|C/Y|35|2.8
Zeiss|Sonnar T* 135mm f/2.8 AE|C/Y|135|2.8
Zeiss|Vario-Sonnar T* 28-85mm f/3.5-4.5 AE|C/Y|28-85|3.5
Zeiss|Tessar T* 45mm f/2.8 AE|C/Y|45|2.8
Zeiss|Makro-Planar T* 60mm f/2.8 AE|C/Y|60|2.8
# ---- Yashica / Contax (C/Y) ----------------------------------------------
Yashica|ML 28mm f/2.8|C/Y|28|2.8
Yashica|ML 35mm f/2.8|C/Y|35|2.8
Yashica|ML 135mm f/2.8|C/Y|135|2.8
Yashica|ML 200mm f/4|C/Y|200|4
Yashica|ML 50mm f/1.4|C/Y|50|1.4
Yashica|DSB 50mm f/1.9|C/Y|50|1.9
Yashica|DSB 28mm f/2.8|C/Y|28|2.8
Yashica|ML 75-150mm f/4|C/Y|75-150|4
Contax|Planar T* 50mm f/1.4|C/Y|50|1.4
Contax|G Biogon T* 28mm f/2.8|G|28|2.8
Contax|G Biogon T* 21mm f/2.8|G|21|2.8
Contax|G Sonnar T* 90mm f/2.8|G|90|2.8
Contax|G Planar T* 45mm f/2|G|45|2
# ---- Konica / Fujica / Topcon / Rollei / Praktica -----------------------
Konica|Hexanon AR 21mm f/2.8|AR|21|2.8
Konica|Hexanon AR 24mm f/2.8|AR|24|2.8
Konica|Hexanon AR 28mm f/3.5|AR|28|3.5
Konica|Hexanon AR 35mm f/2|AR|35|2
Konica|Hexanon AR 40mm f/1.8|AR|40|1.8
Konica|Hexanon AR 50mm f/1.4|AR|50|1.4
Konica|Hexanon AR 50mm f/1.7|AR|50|1.7
Konica|Hexanon AR 57mm f/1.4|AR|57|1.4
Konica|Hexanon AR 85mm f/1.8|AR|85|1.8
Konica|Hexanon AR 100mm f/2.8|AR|100|2.8
Konica|Hexanon AR 135mm f/3.5|AR|135|3.5
Konica|Hexanon AR 200mm f/3.5|AR|200|3.5
Konica|Hexanon AR 300mm f/4.5|AR|300|4.5
Konica|Hexanon AR 40-80mm f/4|AR|40-80|4
Konica|Hexanon AR 80-200mm f/4|AR|80-200|4
Fujica|EBC Fujinon-X 28mm f/3.5|Fujica X|28|3.5
Fujica|EBC Fujinon-X 50mm f/1.4|Fujica X|50|1.4
Fujica|EBC Fujinon-X 55mm f/1.8|Fujica X|55|1.8
Fujica|EBC Fujinon-X 135mm f/3.5|Fujica X|135|3.5
Fujica|EBC Fujinon-X 200mm f/4.5|Fujica X|200|4.5
Fujica|EBC Fujinon-X 43-75mm f/3.5-4.5|Fujica X|43-75|3.5
Topcon|RE Auto-Topcor 35mm f/2.8|Topcon RE|35|2.8
Topcon|RE Auto-Topcor 58mm f/1.4|Topcon RE|58|1.4
Topcon|RE Auto-Topcor 100mm f/2.8|Topcon RE|100|2.8
Topcon|RE Auto-Topcor 135mm f/3.5|Topcon RE|135|3.5
Rollei|Planar HFT 50mm f/1.4|QBM|50|1.4
Rollei|Planar HFT 50mm f/1.8|QBM|50|1.8
Rollei|Sonnar HFT 135mm f/2.8|QBM|135|2.8
Rollei|Distagon HFT 35mm f/2.8|QBM|35|2.8
Rollei|Tele-Tessar HFT 200mm f/4|QBM|200|4
Praktica|Prakticar 50mm f/1.4|Praktica B|50|1.4
Praktica|Prakticar 50mm f/1.8|Praktica B|50|1.8
Praktica|Prakticar 35mm f/2.4|Praktica B|35|2.4
Praktica|Prakticar 135mm f/2.8|Praktica B|135|2.8
Praktica|Prakticar 28mm f/2.8|Praktica B|28|2.8
Praktica|Prakticar 80-200mm f/3.9|Praktica B|80-200|3.9
Praktica|Domiplan 50mm f/2.8|M42|50|2.8
Pentacon|Pentacon Auto 29mm f/2.8|M42|29|2.8
Pentacon|Pentacon Auto 50mm f/1.8|M42|50|1.8
Pentacon|Pentacon Auto 135mm f/2.8|M42|135|2.8
Pentacon|Pentacon Auto 200mm f/4|M42|200|4
Rodenstock|Heligon 50mm f/1.9|Exakta|50|1.9
Enna|Lithagon 28mm f/3.5|M42|28|3.5
Enna|Lithagon 35mm f/3.5|M42|35|3.5
Tamron|SP 17mm f/3.5 Adaptall-2|Adaptall|17|3.5
Tamron|SP 24mm f/2.5 Adaptall-2|Adaptall|24|2.5
Tamron|SP 28mm f/2.5 Adaptall-2|Adaptall|28|2.5
Tamron|SP 35mm f/2.5 Adaptall-2|Adaptall|35|2.5
Tamron|SP 90mm f/2.5 Macro 52B|Adaptall|90|2.5
Tamron|SP 90mm f/2.5 Macro 52BB|Adaptall|90|2.5
Tamron|SP 135mm f/2.5 Adaptall-2|Adaptall|135|2.5
Tamron|SP 300mm f/5.6 Adaptall-2|Adaptall|300|5.6
Tamron|SP 500mm f/8 Mirror 55B|Adaptall|500|8
Tamron|SP 35-80mm f/2.8-3.8 Adaptall-2|Adaptall|35-80|2.8
Tamron|SP 60-300mm f/3.8-5.4 Adaptall-2|Adaptall|60-300|3.8
Tamron|SP 70-210mm f/3.5 Adaptall-2|Adaptall|70-210|3.5
Tamron|SP 80-200mm f/2.8 LD Adaptall-2|Adaptall|80-200|2.8
Tamron|SP 20-40mm f/2.7-3.5 Adaptall-2|Adaptall|20-40|2.7
# ---- Tamron modern (EF/F/A) ----------------------------------------------
Tamron|SP 15-30mm f/2.8 Di VC USD (A012)|EF,F,A|15-30|2.8
Tamron|SP 24-70mm f/2.8 Di VC USD (A007)|EF,F,A|24-70|2.8
Tamron|SP 70-200mm f/2.8 Di VC USD (A009)|EF,F,A|70-200|2.8
Tamron|SP 70-200mm f/2.8 Di VC USD G2 (A025)|EF,F|70-200|2.8
Tamron|SP 35mm f/1.8 Di VC USD (F012)|EF,F,A|35|1.8
Tamron|SP 45mm f/1.8 Di VC USD (F013)|EF,F,A|45|1.8
Tamron|SP 85mm f/1.8 Di VC USD (F016)|EF,F,A|85|1.8
Tamron|SP 90mm f/2.8 Di Macro 1:1 VC USD (F017)|EF,F,A|90|2.8
Tamron|SP 150-600mm f/5-6.3 Di VC USD (A011)|EF,F,A|150-600|5
Tamron|SP 150-600mm f/5-6.3 Di VC USD G2 (A022)|EF,F|150-600|5
Tamron|SP 24-70mm f/2.8 Di VC USD G2 (A032)|EF,F|24-70|2.8
Tamron|17-50mm f/2.8 XR Di II VC (B005)|EF,F,A|17-50|2.8
Tamron|28-75mm f/2.8 XR Di (A09)|EF,F,A|28-75|2.8
Tamron|90mm f/2.8 SP AF Di Macro (272E)|EF,F,A|90|2.8
Tamron|70-300mm f/4-5.6 Di VC USD (A005)|EF,F,A|70-300|4
# ---- Sigma ---------------------------------------------------------------
Sigma|14mm f/1.8 DG HSM Art|EF,F,SA|14|1.8
Sigma|20mm f/1.4 DG HSM Art|EF,F,SA|20|1.4
Sigma|24mm f/1.4 DG HSM Art|EF,F,SA|24|1.4
Sigma|28mm f/1.4 DG HSM Art|EF,F,SA,A,K|28|1.4
Sigma|35mm f/1.4 DG HSM Art|EF,F,SA|35|1.4
Sigma|40mm f/1.4 DG HSM Art|EF,F,SA|40|1.4
Sigma|50mm f/1.4 DG HSM Art|EF,F,SA|50|1.4
Sigma|85mm f/1.4 DG HSM Art|EF,F,SA|85|1.4
Sigma|105mm f/1.4 DG HSM Art|EF,F,SA|105|1.4
Sigma|135mm f/1.8 DG HSM Art|EF,F,SA|135|1.8
Sigma|12-24mm f/4 DG HSM Art|EF,F,SA|12-24|4
Sigma|24-70mm f/2.8 DG OS HSM Art|EF,F,SA|24-70|2.8
Sigma|24-105mm f/4 DG OS HSM Art|EF,F,SA|24-105|4
Sigma|18-35mm f/1.8 DC HSM Art|EF,F,SA,A,K|18-35|1.8
Sigma|50-100mm f/1.8 DC HSM Art|EF,F,SA|50-100|1.8
Sigma|70-200mm f/2.8 DG OS HSM Sport|EF,F,SA|70-200|2.8
Sigma|120-300mm f/2.8 DG OS HSM Sport|EF,F,SA|120-300|2.8
Sigma|150-600mm f/5-6.3 DG OS HSM Sport|EF,F,SA|150-600|5
Sigma|150-600mm f/5-6.3 DG OS HSM Contemporary|EF,F,SA|150-600|5
Sigma|100-400mm f/5-6.3 DG OS HSM Contemporary|EF,F,SA|100-400|5
Sigma|17-70mm f/2.8-4 DC Macro OS HSM Contemporary|EF,F,SA,A,K|17-70|2.8
Sigma|30mm f/1.4 DC HSM Art|EF,F,SA,A,K|30|1.4
Sigma|105mm f/2.8 EX DG OS HSM Macro|EF,F,SA,A,K|105|2.8
Sigma|70mm f/2.8 DG Macro Art|EF,F,SA|70|2.8
Sigma|15mm f/2.8 EX DG Diagonal Fisheye|EF,F,SA,A,K|15|2.8
Sigma|8mm f/3.5 EX DG Circular Fisheye|EF,F,SA,A,K|8|3.5
# ---- Tokina --------------------------------------------------------------
Tokina|AT-X 11-20mm f/2.8 PRO DX|EF,F|11-20|2.8
Tokina|AT-X 14-20mm f/2 PRO DX|EF,F|14-20|2
Tokina|AT-X 16-28mm f/2.8 PRO FX|EF,F|16-28|2.8
Tokina|AT-X 17-35mm f/4 PRO FX|EF,F|17-35|4
Tokina|AT-X 24-70mm f/2.8 PRO FX|EF,F|24-70|2.8
Tokina|AT-X 24-70mm f/4 PRO FX|EF,F|24-70|4
Tokina|AT-X 70-200mm f/4 PRO FX VCM-S|EF,F|70-200|4
Tokina|AT-X 100mm f/2.8 PRO D Macro|EF,F|100|2.8
Tokina|AT-X 107mm f/2.8 Macro|EF,F|107|2.8
Tokina|AT-X 28-70mm f/2.8 PRO|EF,F|28-70|2.8
Tokina|AT-X 80-200mm f/2.8 PRO|EF,F|80-200|2.8
Tokina|AT-X 300mm f/2.8 PRO|EF,F|300|2.8
Tokina|RMC 500mm f/8 Mirror|M42,K|500|8
Tokina|RMC 17mm f/3.5|M42,K|17|3.5
Tokina|SD 24-40mm f/2.8-4|K|24-40|2.8
Tokina|SD 100-300mm f/5.6|K|100-300|5.6
Tokina|SZ-X 70-210mm f/4-5.6|K|70-210|4
# ---- Samyang / Rokinon / Walimex ----------------------------------------
Samyang|8mm f/3.5 UMC Fish-Eye CS II|EF,F,K,A|8|3.5
Samyang|8mm f/2.8 UMC Fish-Eye II|EF,F,K,A|8|2.8
Samyang|14mm f/2.4 XP|EF,F,K|14|2.4
Samyang|24mm f/1.4 ED AS UMC|EF,F,K,A|24|1.4
Samyang|35mm f/1.4 AS UMC|EF,F,K,A|35|1.4
Samyang|50mm f/1.4 AS UMC|EF,F,K,A|50|1.4
Samyang|85mm f/1.4 AS IF UMC|EF,F,K,A|85|1.4
Samyang|100mm f/2.8 ED UMC Macro|EF,F,K,A|100|2.8
Samyang|135mm f/2 ED UMC|EF,F,K,A|135|2
Samyang|500mm f/8 Mirror|M42,T2,EF,F,K|500|8
Samyang|800mm f/8 Mirror|T2,EF,F,K|800|8
Samyang|T-S 24mm f/3.5 ED AS UMC Tilt-Shift|EF,F,K,A|24|3.5
# ---- Laowa / Venus Optics, other modern manual lenses --------------------
Laowa|9mm f/2.8 Zero-D|EF,F,K,A|9|2.8
Laowa|24mm f/14 2X Macro Probe|EF,F,K|24|14
Laowa|25mm f/2.8 2.5-5X Ultra-Macro|EF,F,K,A|25|2.8
Laowa|100mm f/2.8 2X Ultra Macro APO|EF,F,K,A|100|2.8
Laowa|105mm f/2 STF|EF,F,K,A|105|2
Mitakon|Creator 85mm f/2.8 1-5X Super Macro|EF,F,K|85|2.8
Mitakon|Zhongyi 20mm f/2 4.5X Macro|EF,F,K|20|2
TTArtisan|28mm f/5.6|M|28|5.6
TTArtisan|21mm f/1.5|M|21|1.5
TTArtisan|75mm f/2 Rangefinder|M|75|2
TTArtisan|90mm f/1.25|M|90|1.25
TTArtisan|11mm f/2.8 Fisheye|EF,F,K|11|2.8
7Artisans|50mm f/1.1|M|50|1.1
7Artisans|28mm f/1.4|M|28|1.4
7Artisans|35mm f/5.6|M|35|5.6
Meike|8mm f/3.5 Fisheye|EF,F,K,A|8|3.5
Meike|50mm f/2.8 Macro|EF,F|50|2.8
Meike|85mm f/2.8 Macro|EF,F|85|2.8
Viltrox|85mm f/1.8 PFU RBMH|EF,F|85|1.8
Yongnuo|YN 35mm f/2|EF,F|35|2
Yongnuo|YN 50mm f/1.8|EF,F|50|1.8
Yongnuo|YN 50mm f/1.4|EF,F|50|1.4
Yongnuo|YN 85mm f/1.8|EF,F|85|1.8
Yongnuo|YN 100mm f/2|EF,F|100|2
Yongnuo|YN 40mm f/2.8|EF,F|40|2.8
Yongnuo|YN 60mm f/2 Macro|EF,F|60|2
Lomography|Daguerreotype Achromat 64mm f/2.9|EF,F|64|2.9
Lomography|Petzval 85mm f/2.2|EF,F|85|2.2
Lomography|New Petzval 58mm f/1.9|EF,F|58|1.9
Lomography|Russar+ 20mm f/5.6|M|20|5.6
Lomography|Atoll 17mm f/2.8|M|17|2.8
Lomography|Jupiter-3+ 50mm f/1.5|M|50|1.5
Lensbaby|Composer Pro II Sweet 50|EF,F,K|50|2.5
Lensbaby|Velvet 56mm f/1.6|EF,F|56|1.6
Lensbaby|Burnside 35mm f/2.8|EF,F|35|2.8
Lensbaby|Twist 60mm f/2.5|EF,F,K|60|2.5
# ---- Soviet / Eastern Bloc ------------------------------------------------
Helios|44-2 58mm f/2|M42|58|2
Helios|44-3 58mm f/2|M42|58|2
Helios|44-4 58mm f/2|M42|58|2
Helios|44K-4 58mm f/2|K|58|2
Helios|44M-6 58mm f/2|M42|58|2
Helios|44M-7 58mm f/2|M42|58|2
Helios|77M-4 50mm f/1.8|K|50|1.8
Helios|40-2 85mm f/1.5|M42|85|1.5
Industar|Industar-61 L/Z 50mm f/2.8|M42|50|2.8
Industar|Industar-50 50mm f/3.5|M42|50|3.5
Industar|Industar-26M 52mm f/2.8|M39|52|2.8
Industar|Industar-22 50mm f/3.5|M39|50|3.5
Industar|Industar-69 28mm f/2.8|M39|28|2.8
Jupiter|Jupiter-9 85mm f/2|M39|85|2
Jupiter|Jupiter-9 85mm f/2|M42|85|2
Jupiter|Jupiter-11 135mm f/4|M39|135|4
Jupiter|Jupiter-11A 135mm f/4|M42|135|4
Jupiter|Jupiter-12 35mm f/2.8|M39|35|2.8
Jupiter|Jupiter-8 50mm f/2|M39|50|2
Jupiter|Jupiter-3 50mm f/1.5|M39|50|1.5
Jupiter|Jupiter-21M 200mm f/4|M42|200|4
Jupiter|Jupiter-37A 135mm f/3.5|M42|135|3.5
Mir|Mir-1 37mm f/2.8|M42|37|2.8
Mir|Mir-1B 37mm f/2.8|M42|37|2.8
Mir|Mir-24 35mm f/2|M42|35|2
Mir|Mir-10A 28mm f/3.5|M42|28|3.5
Zenit|Zenitar 16mm f/2.8 Fisheye|EF,F,K,M42|16|2.8
Zenit|Zenitar-M 50mm f/1.7|M42|50|1.7
Zenit|Zenitar-M2S 50mm f/1.7|M42|50|1.7
Zenit|Zenitar ME1 50mm f/1.7|M42|50|1.7
Zenit|Rubinar 300mm f/4.5|M42|300|4.5
Zenit|Rubinar 500mm f/5.6 Mirror|M42|500|5.6
Zenit|MC Rubinar 1000mm f/10|M42|1000|10
MTO|MTO-11CA 500mm f/8 Mirror|M42|500|8
MTO|MTO-1000A 1000mm f/10 Mirror|M42|1000|10
Arsenal|Arsat-H 80mm f/2.8|Kiev88|80|2.8
Tair|Tair-3 300mm f/4.5|M42|300|4.5
Tair|Tair-3S 300mm f/4.5|M42|300|4.5
Tair|Tair-11A 135mm f/2.8|M42|135|2.8
Tair|Tair-33 300mm f/4.5|M42|300|4.5
Meyer-Optik|Domiplan 50mm f/2.8|M42|50|2.8
Meyer-Optik|Domiron 50mm f/2|M42|50|2
Meyer-Optik|Orestor 100mm f/2.8|M42|100|2.8
Meyer-Optik|Orestor 135mm f/2.8|M42|135|2.8
Meyer-Optik|Primoplan 58mm f/1.9|M42|58|1.9
Meyer-Optik|Trioplan 100mm f/2.8|M42|100|2.8
Meyer-Optik|Lydith 30mm f/3.5|M42|30|3.5
Meyer-Optik|Orestegor 200mm f/4|M42|200|4
Meyer-Optik|Orestegor 300mm f/4|M42|300|4
Meyer-Optik|Domiplan 50mm f/2.8|Exakta|50|2.8
Carl Zeiss Jena|Pancolar 50mm f/1.8|M42|50|1.8
Carl Zeiss Jena|Pancolar 55mm f/1.4|M42|55|1.4
Carl Zeiss Jena|Flektogon 20mm f/2.8|M42|20|2.8
Carl Zeiss Jena|Flektogon 35mm f/2.8|M42|35|2.8
Carl Zeiss Jena|Sonnar 135mm f/3.5|M42|135|3.5
Carl Zeiss Jena|Tessar 50mm f/2.8|M42|50|2.8
Carl Zeiss Jena|Biotar 58mm f/2|M42|58|2
Schneider|Xenon 50mm f/1.9|Exakta|50|1.9
Schneider|Xenon 50mm f/1.9|M42|50|1.9
Schneider|Xenar 50mm f/2.8|M42|50|2.8
Schneider|Curtagon 35mm f/2.8|M42|35|2.8
Schneider|Curtagon 28mm f/4|M42|28|4
Schneider|Tele-Xenar 135mm f/3.5|M42|135|3.5
Schneider|Tele-Xenar 200mm f/4|M42|200|4
Schneider|Retina-Curtagon 35mm f/2.8|DKL|35|2.8
Schneider|Retina-Xenon 50mm f/1.9|DKL|50|1.9
Schneider|Retina-Tele-Xenar 135mm f/4|DKL|135|4
Schneider|Retina-Curtagon 28mm f/4|DKL|28|4
Steinheil|Auto-D-Tessar 50mm f/2.8|M42|50|2.8
Steinheil|Culminar 135mm f/4|M42|135|4
Steinheil|Quinon 55mm f/1.9|M42|55|1.9
Steinheil|Cassar S 50mm f/2.8|M42|50|2.8
Steinheil|Auto-Quinaron 35mm f/2.8|M42|35|2.8
Steinheil|Cassarit 85mm f/2.8|M42|85|2.8
Steinheil|Tele-Quinar 200mm f/4.5|M42|200|4.5
Vivitar|Series 1 70-210mm f/3.5 Macro Focusing Zoom|K,FD,OM,M42|70-210|3.5
Vivitar|Series 1 28-90mm f/2.8-3.5|K,FD,OM,M42|28-90|2.8
Vivitar|Series 1 35-85mm f/2.8|K,FD,OM,M42|35-85|2.8
Vivitar|Series 1 24mm f/2|K,FD,OM,M42|24|2
Vivitar|Series 1 90mm f/2.5 Macro|K,FD,OM,M42|90|2.5
Vivitar|Series 1 135mm f/2.3|K,FD,OM,M42|135|2.3
Vivitar|Series 1 600mm f/8 Solid Cat|K,FD,OM,M42|600|8
Vivitar|28mm f/2.5 Auto Wide-Angle|M42,K|28|2.5
Vivitar|135mm f/2.8 Auto Telephoto|M42,K|135|2.8
Vivitar|70-150mm f/3.8 Close Focusing|K,FD,M42|70-150|3.8
Vivitar|55mm f/2.8 Macro|K,FD,OM,M42|55|2.8
Vivitar|100mm f/3.5 Macro|K,FD,OM,M42|100|3.5
Petri|Petri C.C Auto 55mm f/1.8|M42|55|1.8
Petri|Petri 28mm f/2.8|M42|28|2.8
Petri|Petri 135mm f/2.8|M42|135|2.8
Petri|Petri 200mm f/3.5|M42|200|3.5
Opteka|Opteka 6.5mm f/3.5 Fisheye|EF,F|6.5|3.5
Ricoh|XR Rikenon 50mm f/1.4|K|50|1.4
Ricoh|XR Rikenon 50mm f/2|K|50|2
Ricoh|XR Rikenon 28mm f/2.8|K|28|2.8
Ricoh|XR Rikenon 135mm f/2.8|K|135|2.8
Ricoh|XR Rikenon 35mm f/2.8|K|35|2.8
Ricoh|Auto Rikenon 55mm f/2.2|M42|55|2.2
Ricoh|Mirror Rikenon 500mm f/8|K|500|8
# ---- Olympus 4/3, Panasonic/Leica D --------------------------------------
Olympus|Zuiko Digital ED 150mm f/2|4/3|150|2
Olympus|Zuiko Digital ED 300mm f/2.8|4/3|300|2.8
Olympus|Zuiko Digital ED 8mm f/3.5 Fisheye|4/3|8|3.5
Panasonic|Leica D Summilux 25mm f/1.4 Asph.|4/3|25|1.4
Panasonic|Leica D Vario-Elmarit 14-50mm f/2.8-3.5 Asph. Mega O.I.S.|4/3|14-50|2.8
# ---- Mamiya / medium format ---------------------------------------------
Mamiya|Sekor C 55mm f/2.8|M645|55|2.8
Mamiya|Sekor C 80mm f/1.9|M645|80|1.9
Mamiya|Sekor C 105mm f/3.5|M645|105|3.5
Mamiya|Sekor C 150mm f/3.5|M645|150|3.5
Mamiya|Sekor C 210mm f/4|M645|210|4
Mamiya|Sekor C 300mm f/5.6|M645|300|5.6
Mamiya|Sekor C 45mm f/2.8|M645|45|2.8
Mamiya|Sekor C 35mm f/3.5|M645|35|3.5
Mamiya|Sekor C 120mm f/4 Macro|M645|120|4
Mamiya|Sekor C 80mm f/2.8 N|M645|80|2.8
Mamiya|Sekor C 55-110mm f/4.5|M645|55-110|4.5
Mamiya|Sekor SX 50mm f/1.4|M42|50|1.4
Mamiya|Sekor SX 135mm f/3.5|M42|135|3.5
Mamiya|Sekor SX 28mm f/3.5|M42|28|3.5
Mamiya|Sekor SX 200mm f/4|M42|200|4
Mamiya|Sekor DTL 135mm f/3.5|M42|135|3.5
Mamiya|Sekor DTL 55mm f/1.8|M42|55|1.8
# ---- Sony A-mount (Minolta lineage) --------------------------------------
Sony|Sonnar T* 135mm f/1.8 ZA (SAL135F18Z)|A|135|1.8
Sony|Planar T* 85mm f/1.4 ZA (SAL85F14Z)|A|85|1.4
Sony|G 70-200mm f/2.8 SSM (SAL70200G)|A|70-200|2.8
Sony|G 70-200mm f/2.8 SSM II (SAL70200G2)|A|70-200|2.8
Sony|G 300mm f/2.8 SSM II (SAL300F28G2)|A|300|2.8
Sony|G 500mm f/4 SSM (SAL500F40G)|A|500|4
Sony|Vario-Sonnar T* 16-35mm f/2.8 ZA SSM (SAL1635Z)|A|16-35|2.8
Sony|Vario-Sonnar T* 24-70mm f/2.8 ZA SSM (SAL2470Z)|A|24-70|2.8
Sony|DT 50mm f/1.8 SAM (SAL50F18)|A|50|1.8
Sony|DT 11-18mm f/4.5-5.6 (SAL1118)|A|11-18|4.5
Sony|DT 16-80mm f/3.5-4.5 ZA (SAL1680Z)|A|16-80|3.5
Sony|135mm f/2.8 [T4.5] STF (SAL135F28)|A|135|2.8
Sony|100mm f/2.8 Macro (SAL100M28)|A|100|2.8
Sony|50mm f/2.8 Macro (SAL50M28)|A|50|2.8
Sony|20mm f/2.8 (SAL20F28)|A|20|2.8
Sony|28mm f/2.8 (SAL28F28)|A|28|2.8
Sony|70-300mm f/4.5-5.6 G SSM (SAL70300G)|A|70-300|4.5
Sony|18-55mm f/3.5-5.6 SAM II (SAL1855-2)|A|18-55|3.5
# ---- Kodak, Argus, Schacht, others ---------------------------------------
Schacht|Travenar 135mm f/3.5|M42|135|3.5
Schacht|Travegon 35mm f/3.5|M42|35|3.5
Schacht|Travenar-R 90mm f/3.5|M42|90|3.5
"""

def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")

def norm(s):
    return re.sub(r"[^a-z0-9]+", "", s.lower())

def main():
    with open(PATH, encoding="utf-8") as f:
        d = json.load(f)
    by_brand = {b["brand"]: b for b in d["brands"]}
    seen = set()
    ids = set()
    for b in d["brands"]:
        for m in b["models"]:
            seen.add((norm(b["brand"]), norm(m["model"]), norm(m["mount"])))
            ids.add(m["id"])
    added = 0
    for ln in DATA.strip().splitlines():
        ln = ln.strip()
        if not ln or ln.startswith("#"):
            continue
        parts = [p.strip() for p in ln.rstrip("|").split("|")]
        brand, model, mounts, focal, ap = parts[:5]
        ap = float(ap)
        for mount in [x.strip() for x in mounts.split(",")]:
            key = (norm(brand), norm(model), norm(mount))
            if key in seen:
                continue
            seen.add(key)
            if "-" in focal:
                lo, hi = [float(x) for x in focal.split("-")]
                lo, hi = int(round(lo)), int(round(hi))
                m = {"type": "zoom", "focal_min": lo, "focal_max": hi}
                fk = lo
            else:
                fl = float(focal)
                fl = int(round(fl))
                m = {"type": "prime", "focal": fl}
                fk = fl
            lid = "%s-%s-%d" % (slug(brand + " " + model), slug(mount), fk)
            n = 2
            base = lid
            while lid in ids:
                lid = "%s-%d" % (base, n); n += 1
            ids.add(lid)
            entry = {"id": lid, "model": model, "mount": mount, "type": m.pop("type"),
                     "max_aperture": ap}
            entry.update(m)
            if brand not in by_brand:
                nb = {"brand": brand, "models": []}
                d["brands"].append(nb)
                by_brand[brand] = nb
            by_brand[brand]["models"].append(entry)
            added += 1
    d["brands"].sort(key=lambda b: b["brand"].lower())
    for b in d["brands"]:
        # keep the original order of existing models; new ones were appended
        pass
    d["_source"] = ("lensfun database, data/ dir (CC BY-SA 3.0) — factual specs only "
                    "(make/model/mount/focal/aperture); generated 2026-10-06; extended "
                    "2026-10-08 with additional factual specs (tools/extend_catalog.py); "
                    "mounts limited to Sony-E-adaptable (flange > 18mm)")
    with open(PATH, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
    tot = sum(len(b["models"]) for b in d["brands"])
    print("added", added, "total", tot, "brands", len(d["brands"]))

if __name__ == "__main__":
    main()
