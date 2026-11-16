#!/usr/bin/env python3
"""ClinicalHarmony data-generator v1 (Phase 9a). Usage: python3 data-generator/generate.py --help"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from hl7gen.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
