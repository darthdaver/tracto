import json
import ollama
import re
import time
import os
import glob

from llama_index.core import Document, VectorStoreIndex
from llama_index.core import SimpleDirectoryReader, VectorStoreIndex, ServiceContext
from llama_index.llms.ollama import Ollama
from llama_index.core import Settings
from llama_index.embeddings.ollama import OllamaEmbedding
import utils  # Assuming utils.model_text2text is defined

#llm = 'deepseek-r1:32b'
#llm = 'phi4'
#llm = "llama3.1:8b"
#llm_list = ['phi4', 'deepseek-r1:32b', 'llama_index']
llm_list = ['llama3.1']

def build_java_index():
    """
    Reads all .java files from the given folder and its subfolders,
    removes lines that contain Java assertions (lines starting with 'assert'),
    and then builds and returns a Llama rag vector index using Ollama for LLM and embeddings.
    """

    # Initialize your LLM and embedding model using Ollama.
    # (These settings appear to be used later for predictions.)
    Settings.llm = Ollama(model="llama3.1")
    ollama.pull("llama3.1")
    ollama.pull("mxbai-embed-large")
    Settings.embed_model = OllamaEmbedding(model_name="mxbai-embed-large")


    # Define the folder path containing the Java files.
    folder_path = (
        "/Users/luca/Documents/USI/tratto-concrete-oracles_local/tratto/src/main/resources/dataset/tokens/input2"
    )

    # Compile a regex pattern to filter out assertion lines.
    # This pattern matches lines that (after optional whitespace) start with 'assert'
    #assertion_pattern = re.compile(r"^\s*assert\s")

    
    documents = SimpleDirectoryReader(folder_path).load_data()
    
    # Build the vector index using the provided service context (Ollama models).
    index = VectorStoreIndex.from_documents(
        documents, show_progress=True
    )

    print("Index built successfully.")
    return index


def read_json(file_path):
    with open(file_path, 'r', encoding='utf-8') as file:
        data = json.load(file)
    return data

def extract_test_and_assertion(test_data):
    """
    Extracts the test code and expected assertion from test_data.
    It removes the assertion line from the test code. It assumes that the 
    assertion is located in the last or second-to-last line of the test code.
    
    Args:
        test_data (dict): A dictionary with keys "testPrefix" (which contains a "body" key)
                          and "target" (the expected assertion).
    
    Returns:
        tuple: (test_code_without_assertion, expected_assertion)
    """
    # Get the code body and split into lines using splitlines() for robustness.
    code_body = test_data["testPrefix"]["body"].strip()
    lines = code_body.splitlines()

    # Define a regex pattern to match common Java assertion statements.
    assertion_pattern = re.compile(
        r'^\s*(?:assert\b|assertEquals\b|assertNotEquals\b|assertTrue\b|assertFalse\b|'
        r'assertNull\b|assertNotNull\b|assertSame\b|assertNotSame\b|assertThat\b|'
        r'Assert\.|Assertions\.)'
    )

    # Check if the last line is an assertion and remove it.
    if lines and assertion_pattern.match(lines[-1]):
        lines.pop()
    # Otherwise, if the second-to-last line exists and is an assertion, remove that.
    elif len(lines) >= 2 and assertion_pattern.match(lines[-2]):
        del lines[-2]

    # Reassemble the remaining lines into a single string.
    test_code = "\n".join(lines)
    expected_assertion = test_data["target"].strip()
    
    return test_code, expected_assertion

def query_model(test_code, llm, llama_index):
    #print(test_code)
    prompt = f"""Given the following Java test code, generate the assertion statement that should be used at the end. 
    Please enclose only the assertion statement between <keep> and </keep> (both). As general example of the format your answer is: <keep>assertEquals(\"99\", variable);</keep>  

    {test_code}

    An example of the format your answer is: <keep>assertEquals(\"99\", variable);</keep>    
    """

    # Check if the model is pulled from ollama

    content = None

    if llm == 'llama_index':
        query_engine = llama_index.as_query_engine(llm=Settings.llm)

        # Perform a query
        content = str(query_engine.query(prompt))
    else:
        response = ollama.chat(
            model=llm, 
            messages=[{"role": "user", "content": prompt}]
        )
    
        content = response['message']['content'].strip()
    
    # Use regex to extract whatever is between <keep> and </keep>
    match = re.search(r'<keep>(.*?)</keep>', content, re.DOTALL)
    if match:
        return match.group(1).strip()
    else:
        # Fallback if no <keep>...</keep> block is found
        return content

def main():

    llama_index = None

    for llm in llm_list:
        print(f"\n\nRunning model: {llm}")
        #ollama.pull(llm)

        if llm == 'llama_index':
            print("Building Llama RAG index...")
            llama_index = build_java_index()

        start_time = time.time()
        timestamp = time.strftime("%Y%m%d-%H%M%S")
        output_file = f"./src/main/resources/dataset/tokens/output/{timestamp}_{llm}_results.json"


        # Set the directory path where your JSON files are stored
        folder_path = "./src/main/resources/dataset/tokens/input"

        # Use glob to list all JSON files in the folder
        json_files = glob.glob(os.path.join(folder_path, "*.json"))

        total_tests = 0
        correct_predictions = 0

        # Loop over each JSON file
        for file_path in json_files:
            print(f"\nProcessing file: {file_path}")
            
            # Open and load the JSON file
            with open(file_path, 'r') as json_file:
                data = json.load(json_file)

            results = []
            total_tests += len(data)
            
            total = len(data)
            
            for idx, item in enumerate(data, start=1):
                try:
                    # Print progress percentage
                    progress = (idx / total) * 100

                    test_code, expected_assertion = extract_test_and_assertion(item)
                    generated_assertion = query_model(test_code, llm, llama_index)
                    
                    match = (generated_assertion == expected_assertion) or (expected_assertion in generated_assertion)
                    if match:
                        correct_predictions += 1
                    
                    results.append({
                        "test_identifier": item["testPrefix"]["identifier"],
                        "incomplete_test": test_code,
                        "generated_assertion": generated_assertion,
                        "expected_assertion": expected_assertion,
                        "match": match
                    })

                    print(f"Prediction {idx} of {total} ({progress:.1f}% complete) is {'Correct' if match else 'Incorrect'}.")

                except Exception as e:
                    print(f"Error: {e}")
                    continue
        
        
        accuracy = (correct_predictions / total_tests) * 100
        print(f"Total tests: {total_tests}")
        print(f"Correct predictions: {correct_predictions}")
        print(f"Accuracy: {accuracy:.2f}%")

        # Compute end time in minutes
        end_time = time.time()
        elapsed_time = (end_time - start_time) / 60
        print(f"Elapsed time: {elapsed_time:.1f} minutes")

        results.append({
            "total_tests": total_tests,
            "correct_predictions": correct_predictions,
            "accuracy": accuracy,
            "total time": elapsed_time,
            "Average time per query": elapsed_time/total_tests
        })

        with open(output_file, 'w', encoding='utf-8') as file:
            json.dump(results, file, indent=4)

if __name__ == "__main__":
    main()