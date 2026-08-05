_current_context = [None, None, None]

cell_values_by_id = {}

reaction_context = None

# Set by run_activity_direct before the first activity executes; shared across all
# concurrent activities (same PyActions instance, stateless — see _ReactionContext doc).
# None during standalone simulation (_framework.py) and model init.
java_actions = None
