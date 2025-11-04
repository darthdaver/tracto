#!/usr/bin/env python3
import argparse
import os.path

import pandas as pd

def main(test_projects_csv, seart_csv, output_csv):
    if not os.path.exists(output_csv):
        os.makedirs(os.path.dirname(output_csv), exist_ok=True)
    # Load test_projects.csv (has header with 'project_id')
    projects = pd.read_csv(test_projects_csv, dtype={"project_id": "string"})
    if "project_id" not in projects.columns:
        raise ValueError("The file test_projects.csv must contain a 'project_id' column.")

    # Load seart-github-repos.csv (NO header). Rename first column to 'project_id'
    seart = pd.read_csv(seart_csv, header=None, dtype="string")
    if seart.shape[1] < 1:
        raise ValueError("The file seart-github-repos.csv appears to have no columns.")
    seart = seart.rename(columns={0: "project_id"})

    # Filter rows where first column matches any project_id
    filtered = seart[seart["project_id"].isin(projects["project_id"].astype("string"))]

    # Write out the result WITHOUT header (to mirror the input format)
    filtered.to_csv(output_csv, index=False, header=False)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Filter seart-github-repos.csv by project_id from test_projects.csv")
    parser.add_argument("--test-projects-path", required=True, help="Path to test_projects.csv (must have 'project_id' column)")
    parser.add_argument("--seart-repos-path", required=True, help="Path to seart-github-repos.csv (no header; first column is project_id)")
    parser.add_argument("--output-path", default="mining_repos.csv", help="Path for output CSV (no header)")
    args = parser.parse_args()
    main(args.test_projects_path, args.seart_repos_path, args.output_path)
