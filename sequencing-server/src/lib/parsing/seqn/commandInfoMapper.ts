import type { SyntaxNode, Tree } from '@lezer/common';
import type { EnumMap, FswCommandArgument } from '@nasa-jpl/aerie-ampcs';

export interface CommandInfoMapper {
  /** format string of multiple arguments */
  formatArgumentArray(values: string[], commandNode: SyntaxNode | null): string;

  /** get insert position for missing arguments */
  getArgumentAppendPosition(node: SyntaxNode | null): number | undefined;

  /** gets container of arguments from subtree */
  getArgumentNodeContainer(commandNode: SyntaxNode | null): SyntaxNode | null;

  /** collects argument nodes from sub-tree of this command argument container */
  getArgumentsFromContainer(containerNode: SyntaxNode): SyntaxNode[];

  /** ascends parse tree to find scope to display in form editor */
  getContainingCommand(node: SyntaxNode | null): SyntaxNode | null;

  getDefaultValueForArgumentDef(argDef: FswCommandArgument, enumMap: EnumMap): string;

  /** finds the node in the parse tree containing the name */
  getNameNode(stepNode: SyntaxNode | null): SyntaxNode | null;

  /**
   * collect variables for populating select box, cursor position is used to limit scope to containing
   * sequence in multiple sequence per file languages
   */
  // Consider filtering by type or allowing adaptation to filter by type
  // not clear at this point what FSW does in terms of type coercion and
  // what variable types are used for enums arguments
  getVariables(docText: string, tree: Tree, cursor: number): string[];

  /** is argument node a variable, false implies literal */
  isArgumentNodeOfVariableType(argNode: SyntaxNode | null): boolean;

  /** checks if select list should be used */
  nodeTypeEnumCompatible(node: SyntaxNode | null): boolean;

  /** checks if node has knowable argument types */
  nodeTypeHasArguments(node: SyntaxNode | null): boolean;

  /** checks if numeric argument editor should be displayed */
  nodeTypeNumberCompatible(node: SyntaxNode | null): boolean;
}
