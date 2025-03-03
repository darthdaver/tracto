from argparse import ArgumentParser
import os

class EwashArgsParser:
    @staticmethod
    def add_args(parser):
        parser.add_argument(
            "--input",
            type=str,
            required=True,
            help="The path to the input file or directory (containing the files to process)."
        )
        parser.add_argument(
            "--output",
            type=str,
            required=True,
            help="The path to the output directory (where to save the processed files)."
        )
        parser.add_argument(
            "--config",
            type=str,
            default=os.path.join(os.path.abspath(os.path.dirname(__file__)), '..', 'resources', 'ewash_config.json'),
            required=False,
            help="The path to the configuration file for the E-WASH approach."
        )
        parser.add_argument(
            "--intros",
            type=str,
            default=os.path.join(os.path.abspath(os.path.dirname(__file__)), '..', 'resources', 'ewash_intros.json'),
            required=False,
            help="The path to the dictionary of intro comments for each section of the input for the E-WASH approach."
        )