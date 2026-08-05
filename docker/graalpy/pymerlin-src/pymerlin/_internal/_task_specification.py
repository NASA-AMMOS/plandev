class TaskInstance:
    """
    A TaskInstance is just a lambda with extra steps
    """
    def __init__(self, func):
        self.func = func

    # def validate(self):
    #     return [
    #         validation(self.args)
    #         for validation in self.validations
    #     ]

    # def __repr__(self):
    #     return self.repr

    def run(self):
        return self.func()
