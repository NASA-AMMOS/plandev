#!/usr/bin/env python3
"""Migrate the database of an Aerie venue."""

import os
import argparse
import sys
import shutil
import subprocess
from dotenv import load_dotenv
import requests

def clear_screen():
  os.system('cls' if os.name == 'nt' else 'clear')

def print_error(message: str):
  """
  Print an error message to the terminal

  :param message: Message to be printed.
  """
  print("\033[91mError\033[0m: " + message)

def exit_with_error(message: str, exit_code=1):
  """
  Exit the program with the specified error message and exit code.

  :param message: Error message to display before exiting.
  :param exit_code: Error code to exit with. Defaults to 1.
  """
  print_error(message)
  sys.exit(exit_code)


class Hasura:
  """
  Class for communicating with Hasura via the CLI and API.
  """
  command_suffix = ''
  migrate_suffix = ''
  endpoint = ''
  admin_secret = ''
  db_name = 'Aerie'
  current_version = 0

  def __init__(self, endpoint: str, admin_secret: str, hasura_path: str,  env_path: str, db_name='Aerie'):
    """
    Initialize a Hasura object.

    :param endpoint: The http(s) endpoint for the Hasura instance.
    :param admin_secret: The admin secret for the Hasura instance.
    :param hasura_path: The directory containing the config.yaml and migrations folder for the Hasura instance.
    :param env_path: The path to the envfile, if provided.
    :param db_name: The name that the Hasura instance calls the database. Defaults to 'Aerie'.
    """
    self.admin_secret = admin_secret
    self.db_name = db_name

    # Sanitize endpoint
    self.endpoint = endpoint
    self.endpoint = self.endpoint.strip()
    self.endpoint = self.endpoint.rstrip('/')

    # Set up the suffix flags to use when calling the Hasura CLI
    self.command_suffix = f'--skip-update-check --project {hasura_path}'
    if env_path:
      self.command_suffix += f' --envfile {env_path}'

    # Set up the suffix flags to use when calling the 'migrate' subcommand on the CLI
    self.migrate_suffix = f"--database-name {self.db_name} --endpoint {self.endpoint} --admin-secret '{self.admin_secret}'"

    # Check that Hasura CLI is installed
    if not shutil.which('hasura'):
      exit_with_error(f'Hasura CLI is not installed. Exiting...')
    else:
      self.execute('version')

    # Mark the current schema version in Hasura
    self.current_version = self.mark_current_version()

  def execute(self, subcommand: str, flags='', no_output=False) -> int:
    """
    Execute an arbitrary "hasura" command.

    :param subcommand: The subcommand to execute.
    :param flags: The flags to be passed to the subcommand.
    :param no_output: If true, swallows both STDERR and STDOUT output from the command.
    :return: The exit code of the command.
    """
    command = f'hasura {subcommand} {flags} {self.command_suffix}'
    if no_output:
      command += ' > /dev/null 2>&1'
    return os.system(command)

  def migrate(self, subcommand: str, flags='', no_output=False) -> int:
    """
    Execute a "hasura migrate" subcommand.

    :param subcommand: A subcommand of "hasura migrate"
    :param flags: Flags specific to the subcommand call to be passed.
    :param no_output: If true, swallows both STDERR and STDOUT output from the command.
    :return: The exit code of the command.
    """
    command = f'hasura migrate {subcommand} {flags} {self.migrate_suffix} {self.command_suffix}'
    if no_output:
      command += ' > /dev/null 2>&1'
    return os.system(command)

  def get_migrate_output(self, subcommand: str, flags='') -> [str]:
    """
    Get the output of a "hasura migrate" subcommand.

    :param subcommand: A subcommand of "hasura migrate"
    :param flags: Flags specific to the subcommand call to be passed.
    :return: The STDOUT response of the subcommand, split on newlines.
    """
    command = f'hasura migrate {subcommand} {flags} {self.migrate_suffix} {self.command_suffix}'
    return subprocess.getoutput(command).split("\n")

  def get_migrate_status(self, flags='') -> str:
    """
    Execute 'hasura migrate status' and format the output.

    :param flags: Any additional flags to be passed to 'hasura migrate status'
    :return: The output of the CLI command with the first three lines removed
    """
    output = self.get_migrate_output('status', flags)
    del output[0:3]
    return output

  def mark_current_version(self) -> int:
    """
    Queries the database behind the Hasura instance for its current schema information.
    Ensures that all applied migrations are marked as "applied" in Hasura's internal migration tracker.

    :return: The migration the underlying database is currently on
    """
    # Query the database
    run_sql_url = f'{self.endpoint}/v2/query'
    headers = {
      "content-type": "application/json",
      "x-hasura-admin-secret": self.admin_secret,
      "x-hasura-role": "admin"
    }
    body = {
      "type": "run_sql",
      "args": {
        "source": self.db_name,
        "sql": "SELECT migration_id FROM migrations.schema_migrations;",
        "read_only": True
      }
    }
    session = requests.Session()
    resp = session.post(url=run_sql_url, headers=headers, json=body)
    if not resp.ok:
      exit_with_error("Error while fetching current schema information.")

    migration_ids = resp.json()['result']
    if migration_ids.pop(0)[0] != 'migration_id':
      exit_with_error("Error while fetching current schema information.")

    # Get the current migration status from Hasura's perspective for comparison
    migrate_status = self.get_migrate_status()

    # migration_ids now looks like [['0'], ['1'], ... ['n']]
    prev_id = -1
    cur_id = 0
    for i in migration_ids:
      cur_id = int(i[0])
      if cur_id != prev_id + 1:
        exit_with_error(f'Gap detected in applied migrations. \n\tLast migration: {prev_id} \tNext migration: {cur_id}'
                        f'\n\tTo resolve, manually revert all migrations following {prev_id}, then run this script again.')

      # Skip marking a migration as applied if it is already applied
      split = migrate_status[cur_id].split()
      if split[0] == i[0] and len(split) == 4:
        prev_id = cur_id
        continue

      # If a migration is not marked as applied, mark it as such
      self.migrate('apply', f'--skip-execution --version {cur_id}', no_output=True)
      prev_id = cur_id

    return cur_id

  def reload_metadata(self):
    """
    Apply and reload the metadata.
    """
    self.execute('metadata apply')
    self.execute('metadata reload')


class DB_Migration:
  """
  Container class for Migration steps to be applied/reverted.
  """
  steps = []
  migrations_folder = ''
  def __init__(self, migrations_folder: str, reverse: bool):
    """
    :param migrations_folder: Folder where the migrations are stored.
    :param reverse: If true, reverses the list of migration steps.
    """
    self.migrations_folder = migrations_folder
    try:
      for root, dirs, files in os.walk(migrations_folder):
        if dirs:
          self.add_migration_step(dirs)
    except FileNotFoundError as fne:
      exit_with_error(str(fne).split("]")[1])
    if len(self.steps) <= 0:
      exit_with_error("No database migrations found.")
    if reverse:
      self.steps.reverse()

  def add_migration_step(self, _migration_step):
    self.steps = sorted(_migration_step, key=lambda x: int(x.split('_')[0]))

  def get_available_steps(self, hasura: Hasura, apply: bool) -> ([], str):
    """
    Filter out the steps that can't be applied given the current mode and currently applied steps
    :param hasura: Hasura object connected to the venue to be migrated
    :param apply: Whether migrations will be applied or reverted
    :return: The subset of available steps, and a print-ready string declaring what those steps are.
    """
    display_string = "\n\033[4mMIGRATION STEPS AVAILABLE:\033[0m\n"
    _output = hasura.get_migrate_status()
    display_string += _output[0] + "\n"

    available_steps = self.steps.copy()
    for i in range(1, len(_output)):
      split = list(filter(None, _output[i].split(" ")))

      if len(split) >= 5 and "Not Present" == (split[2] + " " + split[3]):
        exit_with_error("Migration files exist on server that do not exist on this machine. "
                        "Synchronize files and try again.\n")

      folder = os.path.join(self.migrations_folder, f'{split[0]}_{split[1]}')
      if apply:
        # If there are four words, they must be "<NUMBER> <MIGRATION NAME> Present Present"
        if (len(split) == 4 and "Present" == split[-1]) or (not os.path.isfile(os.path.join(folder, 'up.sql'))):
          available_steps.remove(f'{split[0]}_{split[1]}')
        else:
          display_string += _output[i] + "\n"
      else:
        # If there are only five words, they must be "<NUMBER> <MIGRATION NAME> Present Not Present"
        if (len(split) == 5 and "Not Present" == (split[-2] + " " + split[-1])) or (
        not os.path.isfile(os.path.join(folder, 'down.sql'))):
          available_steps.remove(f'{split[0]}_{split[1]}')
        else:
          display_string += _output[i] + "\n"

    return available_steps, display_string

def step_by_step_migration(hasura: Hasura, db_migration: DB_Migration, apply: bool):
  """
  Migrate the database one migration at a time until there are no more migrations left or the user decides to quit.

  :param hasura: Hasura object connected to the venue to be migrated
  :param db_migration: DB_Migration containing the complete list of migrations available
  :param apply: Whether to apply or revert migrations
  """
  # Get only the available migration steps
  available_steps, display_string = db_migration.get_available_steps(hasura, apply)
  if available_steps:
    print(display_string)
  else:
    print("\nNO MIGRATION STEPS AVAILABLE\n")

  for step in available_steps:
    print("\033[4mCURRENT STEP:\033[0m\n")
    timestamp = int(step.split("_")[0])

    if apply:
      hasura.migrate('apply', f'--version {timestamp} --dry-run --log-level WARN')
    else:
      hasura.migrate('apply', f'--version {timestamp} --type down --dry-run --log-level WARN')

    print()
    _value = ''
    while _value != "y" and _value != "n" and _value != "q" and _value != "quit":
      if apply:
        _value = input(f'Apply {step}? (y/n/\033[4mq\033[0muit): ').lower()
      else:
        _value = input(f'Revert {step}? (y/n/\033[4mq\033[0muit): ').lower()

    if _value == "q" or _value == "quit":
      hasura.reload_metadata()
      sys.exit()
    if _value == "y":
      if apply:
        print('Applying...')
        exit_code = hasura.migrate('apply', f'--version {timestamp} --type up')
        print()
        if exit_code != 0:
          hasura.reload_metadata()
          return
        if not hasura.apply_after(timestamp, apply):
          hasura.reload_metadata()
          exit_with_error("Incomplete 'after' tasks, cannot proceed.", 2)
      else:
        print('Reverting...')
        exit_code = hasura.migrate('apply', f'--version {timestamp} --type down')
        print()
        if exit_code != 0:
          hasura.reload_metadata()
          return

    elif _value == "n":
      hasura.reload_metadata()
      return
  hasura.reload_metadata()
  input("Press Enter to continue...")


def bulk_migration(hasura: Hasura, db_migration: DB_Migration, apply: bool):
  """
  Migrate the database until there are no migrations left to be applied[reverted].

  :param hasura: Hasura object connected to the venue to be migrated
  :param db_migration: Set of migrations to be applied
  :param apply: Whether to apply or revert migrations.
  """
  # Migrate the database
  exit_with = 0
  if apply:
    # Get only the available migration steps
    available_steps, display_string = db_migration.get_available_steps(hasura, apply)
    for step in available_steps:
      timestamp = int(step.split("_")[0])

      # Display dry-run message
      hasura.migrate('apply', f'--version {timestamp} --type up --dry-run --log-level WARN')

      exit_code = hasura.migrate('apply', f'--version {timestamp} --type up')
      if exit_code != 0:
        exit_with = 2
        break
      if not hasura.apply_after(timestamp, apply):
        print_error("Incomplete 'after' tasks, cannot proceed.")
        exit_with = 2
        break
  else:
    hasura.migrate('apply', f'--down {hasura.current_version} --dry-run --log-level WARN')
    exit_code = hasura.migrate('apply', f'--down {hasura.current_version}')
    if exit_code != 0:
      exit_with = 1

  hasura.reload_metadata()

  # Show the result after the migration
  print(f'\n###############'
        f'\nDatabase Status'
        f'\n###############')
  _output = hasura.get_migrate_output('status')
  del _output[0:3]
  print("\n".join(_output))
  exit(exit_with)


def migrate(args: argparse.Namespace):
  """
  Handle the 'migrate' subcommand.

  :param args: The arguments passed to the script.
  """
  hasura = create_hasura(arguments)

  clear_screen()
  print(f'\n###############################'
        f'\nAERIE DATABASE MIGRATION HELPER'
        f'\n###############################'
        f'\n\nMigrating database at {hasura.endpoint}')
  # Find all migration folders for the database
  migration_path = os.path.abspath(args.hasura_path + "/migrations/Aerie")
  migration = DB_Migration(migration_path, args.revert)

  # Enter step-by-step mode if not otherwise specified
  if not args.all:
    # Go step-by-step through the migrations available for the selected database
    step_by_step_migration(hasura, migration, args.apply)
  else:
    bulk_migration(hasura, migration, args.apply)


def status(args: argparse.Namespace):
  """
  Handle the 'status' subcommand.

  :param args: The arguments passed to the script.
  """
  hasura = create_hasura(args)

  clear_screen()
  print(f'\n###############################'
        f'\nAERIE DATABASE MIGRATION STATUS'
        f'\n###############################'
        f'\n\nDisplaying status of database at {hasura.endpoint}')

  display_string = f"\n\033[4mMIGRATION STATUS:\033[0m\n"
  display_string += "\n".join(hasura.get_migrate_status())
  print(display_string)


def create_hasura(args: argparse.Namespace) -> Hasura:
  """
  Create a Hasura object from the CLI arguments

  :param args: Namespace containing the CLI arguments passed to the script. Relevant fields in Namespace:
    - hasura_path (mandatory): Directory containing the config.yaml and migrations folder for the venue
    - env_path (optional): Envfile to load envvars from
    - endpoint (optional): Http(s) endpoint for the venue's Hasura instance
    - admin_secret (optional): Admin secret for the venue's Hasura instance
  :return: A Hasura object connected to the specified instance
  """
  if args.env_path:
    if not os.path.isfile(args.env_path):
      exit_with_error(f'Specified envfile does not exist: {args.env_path}')
    load_dotenv(args.env_path)

  # Grab the credentials from the environment if needed
  hasura_endpoint = args.endpoint if args.endpoint else os.environ.get('HASURA_GRAPHQL_ENDPOINT', "")
  hasura_admin_secret = args.admin_secret if args.admin_secret else os.environ.get('HASURA_GRAPHQL_ADMIN_SECRET', "")

  if not (hasura_endpoint and hasura_admin_secret):
    (e, s) = loadConfigFile(hasura_endpoint, hasura_admin_secret, args.hasura_path)
    hasura_endpoint = e
    hasura_admin_secret = s

  return Hasura(endpoint=hasura_endpoint,
                admin_secret=hasura_admin_secret,
                db_name="Aerie",
                hasura_path=os.path.abspath(args.hasura_path),
                env_path=os.path.abspath(args.env_path) if args.env_path else None)


def loadConfigFile(endpoint: str, secret: str, config_folder: str) -> (str, str):
  """
  Extract the endpoint and admin secret from a Hasura config file.
  Values passed as arguments take priority over the contents of the config file.

  :param endpoint: Initial value of the endpoint for Hasura. Will be extracted if empty.
  :param secret: Initial value of the admin secret for Hasura. Will be extracted if empty.
  :param config_folder: Folder to look for the config file in.
  :return: A tuple containing the Hasura endpoint and the Hasura admin secret.
  """
  hasura_endpoint = endpoint
  hasura_admin_secret = secret

  # Check if config.YAML exists
  configPath = os.path.join(config_folder, 'config.yaml')
  if not os.path.isfile(configPath):
    # Check for .YML
    configPath = os.path.join(config_folder, 'config.yml')
    if not os.path.isfile(configPath):
      errorMsg = "HASURA_GRAPHQL_ENDPOINT and HASURA_GRAPHQL_ADMIN_SECRET" if not endpoint and not secret \
        else "HASURA_GRAPHQL_ENDPOINT" if not endpoint \
        else "HASURA_GRAPHQL_ADMIN_SECRET"
      errorMsg += " must be defined by either environment variables or in a config.yaml located in " + config_folder + "."
      exit_with_error(errorMsg)

  # Extract admin secret and/or endpoint from the config.yaml, if they were not already set
  with open(configPath) as configFile:
    for line in configFile:
      if hasura_endpoint and hasura_admin_secret:
        break
      line = line.strip()
      if line.startswith("endpoint") and not hasura_endpoint:
        hasura_endpoint = line.removeprefix("endpoint:").strip()
        continue
      if line.startswith("admin_secret") and not hasura_admin_secret:
        hasura_admin_secret = line.removeprefix("admin_secret:").strip()
        continue

  if not hasura_endpoint or not hasura_admin_secret:
    errorMsg = "HASURA_GRAPHQL_ENDPOINT and HASURA_GRAPHQL_ADMIN_SECRET" if not hasura_endpoint and not hasura_admin_secret \
      else "HASURA_GRAPHQL_ENDPOINT" if not hasura_endpoint \
      else "HASURA_GRAPHQL_ADMIN_SECRET"
    errorMsg += " must be defined by either environment variables or in a config.yaml located in " + config_folder + "."
    exit_with_error(errorMsg)

  return hasura_endpoint, hasura_admin_secret


def createArgsParser() -> argparse.ArgumentParser:
  """
  Create an ArgumentParser for this script.
  """
  # Create a cli parser
  parser = argparse.ArgumentParser(description=__doc__)
  parent_parser = argparse.ArgumentParser(add_help=False)
  subparser = parser.add_subparsers(title='commands', metavar="<command>")

  # Add global arguments to Parent parser
  parent_parser.add_argument(
    '-p', '--hasura-path',
    dest='hasura_path',
    help='directory containing the config.yaml and migrations folder for the venue. defaults to ./hasura',
    default='./hasura')

  parent_parser.add_argument(
    '-e', '--env-path',
    dest='env_path',
    help='envfile to load envvars from.')

  parent_parser.add_argument(
    '--endpoint',
    help="http(s) endpoint for the venue's Hasura instance",
    required=False)

  parent_parser.add_argument(
    '--admin-secret',
    dest='admin_secret',
    help="admin secret for the venue's Hasura instance",
    required=False)

  # Add 'status' subcommand
  status_parser = subparser.add_parser(
    'status',
    help='Get the current migration status of the database',
    description='Get the current migration status of the database.',
    parents=[parent_parser])

  status_parser.set_defaults(func=status)

  # Add 'migrate' subcommand
  migrate_parser = subparser.add_parser(
    'migrate',
    help='Migrate the database',
    description='Migrate the database.',
    parents=[parent_parser])
  migrate_parser.set_defaults(func=migrate)

  # Applying and Reverting are exclusive arguments
  exclusive_args = migrate_parser.add_mutually_exclusive_group(required=True)

  # Add arguments
  exclusive_args.add_argument(
    '-a', '--apply',
    help='apply migration steps to the database',
    action='store_true')

  exclusive_args.add_argument(
    '-r', '--revert',
    help='revert migration steps to the databases',
    action='store_true')

  migrate_parser.add_argument(
    '--all',
    help='apply[revert] ALL unapplied[applied] migration steps to the database',
    action='store_true')

  return parser


if __name__ == "__main__":
  # Generate arguments and kick off correct subfunction
  arguments = createArgsParser().parse_args()
  try:
    arguments.func(arguments)
  except AttributeError:
    createArgsParser().print_help()
