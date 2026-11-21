#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Rebuild Home's Australian postcode list (app/src/main/assets/au_postcodes.tsv), which Config > Weather uses to
# turn a postcode into its suburbs. Source: GeoNames postal codes for AU (CC BY 4.0, www.geonames.org).
# Delivery, business and mail centres and PO boxes are left out: they aren't places anyone lives.
# Columns: postcode, suburb, state, latitude, longitude.
set -eu
cd "$(dirname "$0")"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
curl -sSfL -o "$tmp/AU.zip" https://download.geonames.org/export/zip/AU.zip
unzip -q -o "$tmp/AU.zip" AU.txt -d "$tmp"
awk -F'\t' -v OFS='\t' '$3 !~ / (Dc|Bc|Mc|Msc|Po|Lpo|Cmc)$/ { print $2, $3, $5, $10, $11 }' "$tmp/AU.txt" \
    | LC_ALL=C sort -t$'\t' -k1,1 -k2,2 -u > app/src/main/assets/au_postcodes.tsv
wc -l app/src/main/assets/au_postcodes.tsv
