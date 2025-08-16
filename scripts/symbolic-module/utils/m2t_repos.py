import os
import sys
import json
import requests
import argparse
import math
import time
from dotenv import load_dotenv


class M2TReposParser:

    def __init__(self, root_dir_arg):
        self.root_dir = root_dir_arg
        self.output_dir = os.path.join(root_dir_arg, "output", "m2t")
        self.rate_limit = {
            "limit": math.inf,
            "remaining": math.inf,
            "reset": 0
        }

    def check_rate_limit(self):
        if self.rate_limit["remaining"] == 0:
            sleep_time = self.rate_limit["reset"] - time.time()
            if sleep_time > 0:
                print(f"Rate limit exceeded. Sleeping for {sleep_time + 10} seconds...")
                time.sleep(sleep_time + 10)
        else:
            print(f"Rate limit remaining: {self.rate_limit['remaining']} " +
                  f"(consumed: {self.rate_limit['limit'] - self.rate_limit['remaining']})")

    def generate_output_dir(self):
        if not os.path.exists(self.output_dir):
            os.makedirs(self.output_dir)

    def update_rate_limit(self, response_headers_arg):
        self.rate_limit = {
            "limit": int(response_headers_arg.get('X-RateLimit-Limit', 0)),
            "remaining": int(response_headers_arg.get('X-RateLimit-Remaining', 0)),
            "reset": int(response_headers_arg.get('X-RateLimit-Reset', 0))
        }

    @staticmethod
    def generate_m2t_repos_dict(m2t_dataset_dir_arg):
        repos = {}
        for root, dirs, files in os.walk(m2t_dataset_dir_arg):
            for file in files:
                if file.endswith(".json"):
                    with open(os.path.join(root, file), 'r') as f:
                        metadata = json.load(f)
                        repo_id = metadata["repository"]["repo_id"]
                        if repo_id not in repos:
                            print(f"Added repository: {repo_id} - {metadata['repository']['url']}")
                            repos[repo_id] = metadata["repository"]["url"]
        return repos

    @staticmethod
    def load_m2t_original_repos_dict(dict_path_arg):
        with open(dict_path_arg, 'r') as f:
            return json.load(f)

    @staticmethod
    def perform_api_request(path_list_arg):
        access_token = os.getenv('GITHUB_TOKEN')
        base_url = "https://api.github.com"
        headers = {
            "Authorization": f"token {access_token}",
            "Accept": "application/vnd.github.v3+json"
        }
        request_url = f"{base_url}/{'/'.join(path_list_arg)}"
        response = requests.get(request_url, headers=headers)
        return response

    @staticmethod
    def print_api_request_error(response_arg, message_arg, identifiers_arg):
        print(f"{message_arg}:{' - '.join(identifiers_arg)}")
        print(f"Response status code: {response_arg.status_code}")
        print(f"Response content: {response_arg.content}")

    def process_repos(self, m2t_repos_arg):
        processed = []
        unprocessed = {}
        for repo_id, repo_url in m2t_repos_arg.items():
            print(f"Processing repository: {repo_id} {repo_url}")
            repo_url_decomposed = repo_url.split("/")
            repo_owner = repo_url_decomposed[-2]
            repo_name = repo_url_decomposed[-1]
            path_list = ["repos", repo_owner, repo_name]

            print(f"Requesting repository info...")
            response = self.perform_api_request(path_list)
            self.update_rate_limit(response.headers)
            self.check_rate_limit()

            if response.status_code == 200:
                print(f"Repository info received...")
                repo_info = response.json()
                path_list = ["repos", repo_owner, repo_name, "commits"]

                print(f"Requesting commits of repository...")
                response = self.perform_api_request(path_list)
                self.update_rate_limit(response.headers)
                self.check_rate_limit()
                if response.status_code == 200:
                    print(f"Commits received...")
                    commits = response.json()
                    last_commit = commits[0]
                    repo_info["last_commit"] = {
                        "sha": last_commit["sha"],
                        "date": last_commit["commit"]["author"]["date"],
                        "api_url": last_commit["url"],
                        "html_url": last_commit["html_url"]
                    }
                    processed.append({
                        "id": repo_info["id"],
                        "name": repo_info["name"],
                        "owner": repo_info["owner"]["login"],
                        "html_url": repo_info["html_url"],
                        "api_url": repo_info["url"],
                        "fork": repo_info["fork"],
                        "forks_count": repo_info["forks_count"],
                        "size": repo_info["size"],
                        "open_issues_count": repo_info["open_issues_count"],
                        "stargazers_count": repo_info["stargazers_count"],
                        "watchers_count": repo_info["watchers_count"],
                        "language": repo_info["language"],
                        "last_commit": repo_info["last_commit"]
                    })
                    print(f"Repository successfully processed.")
                else:
                    self.print_api_request_error(
                        response,
                        "Failed to retrieve commits",
                        [repo_owner, repo_name, repo_url]
                    )
                    unprocessed[repo_id] = repo_url
            else:
                self.print_api_request_error(
                    response,
                    "Failed to retrieve repository info",
                    [repo_owner, repo_name, repo_url]
                )
                unprocessed[repo_id] = repo_url
        return processed, unprocessed

    def save_m2t_result(self, obj, filename):
        with open(os.path.join(self.output_dir, filename), 'w') as result_file:
            json.dump(obj, result_file, indent=4)


if __name__ == "__main__":
    # Create argument parser object
    parser = argparse.ArgumentParser(description='Description of your script')
    # Add arguments
    parser.add_argument('--dataset', type=str, help='Path to M2T dataset')
    parser.add_argument('--dict', type=str, help='Path to M2T pre-processed dictionary')
    # Parse arguments from command line
    args = parser.parse_args()
    # Check arguments
    if len(sys.argv) < 2:
        print("Usage: python3 m2t_repo2csv.py --<operation> <path_to_m2t_dataset_or_dict>")
        sys.exit(1)

    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    load_dotenv(os.path.join(root_dir, ".env"))

    m2t_repos_parser = M2TReposParser(root_dir)
    m2t_repos_parser.generate_output_dir()

    if args.dict:
        with open(args.dict, 'r') as dict_file:
            m2t_repos = json.load(dict_file)
    else:
        if not args.dataset:
            print("Please provide a path to M2T dataset or a pre-processed dictionary (--dataset argument missing).")
            sys.exit(1)
        print("Collecting repositories from M2T dataset...")
        m2t_dataset_dir = args.dataset
        m2t_repos = m2t_repos_parser.generate_m2t_repos_dict(m2t_dataset_dir)
        m2t_repos_parser.save_m2t_result(m2t_repos, "m2t_repos_dict.json")
        print(f"Total repositories: {len(m2t_repos)}")

    print("Processing repositories...")
    processed_repos, unprocessed_repos = m2t_repos_parser.process_repos(m2t_repos)
    m2t_repos_parser.save_m2t_result(processed_repos, "m2t_repos.json")
    m2t_repos_parser.save_m2t_result(unprocessed_repos, "m2t_repos_failed.json")
    print("Total repositories processed: ", len(processed_repos))
    print("Total repositories failed to process: ", len(unprocessed_repos))
