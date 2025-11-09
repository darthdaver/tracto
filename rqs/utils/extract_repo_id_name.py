import re
import sys
import csv

if __name__ == "__main__":
    file_name = sys.argv[1]
    github_repos_file_path = sys.argv[2]
    repo_id = file_name.split('-')[1]
    with open(github_repos_file_path, 'r') as github_repos_file:
        reader = csv.reader(github_repos_file)
        for row in reader:
            if row[0] == repo_id:
                # Return to the bash script the repo identifier and the repo name
                print(f"{repo_id} {row[1]}")
                sys.exit(0)
    # If not found, return an error
    sys.exit(1)