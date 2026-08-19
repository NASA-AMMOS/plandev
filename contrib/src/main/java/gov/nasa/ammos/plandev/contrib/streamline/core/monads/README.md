# Monads - Quick Explanation

We'll briefly motivate and describe monads here.
For a better explanation of monads, see https://bartoszmilewski.com/2016/11/21/monads-programmers-definition/,
on which this is based.

## Motivation - Wrapper Types

We often want to "augment" a value in some way.
For example, we augment a type `A` into an `Optional<A>` to add the idea that a computation can fail.
We can think of `Optional` as a function, from the type `A` to the type `Optional<A>`.
Any type with a type parameter is a function like this, called a "type functor".

We'd like these wrappers to be transparent, in the sense that we write code for `A` and run it on `Optional<A>`.
Equivalently, we want an operator that takes a function `A -> B`
and returns a function `Optional<A> -> Optional<B>`.
More generally, for any type functor `M`, we want an operator from `A -> B` to `M A -> M B`.
This operator is called `map` in general, and is given by `Optional.map`.

We'd also like to extend this to functions with multiple arguments.
We could define `map` overloads taking multiple arguments directly, but there's a more elegant solution.
First, we "curry" the function: Instead of a multi-argument function like this: `A x B -> C`,
we use a function returning a function, like this: `A -> (B -> C)`.
This lets us "bake in" each argument, one at a time.
Now, imagine we `map` this function, and apply it to an `M A`: We'd get a result of type `M (B -> C)`.
Now, we want an operator that can apply this wrapped function to a wrapped value of type `M B`.

Indeed, such an operation is called `apply`.
It takes a wrapped function, and turns it into a function on wrapped values: `M (B -> C) -> (M B -> M C)`.
There's no direct equivalent of `apply` for `Optional`.
This looks similar to `map`, though. If we had a way to wrap the function we gave to `map`, we could use `apply` instead.

The operator which wraps a value is called `pure` (or sometimes `unit`, but we use that word for other purposes.)
It has the signature `A -> M A`, and should be thought of as adding an "emtpy" or "default" wrapper.
For `Optional`, this is `Optional.of`.
Type functors with both `pure` and `apply` are called "applicative functors", or just "applicatives".

Sometimes this isn't enough. Sometimes we need to add wrapper information as we compute.
For example, consider an implementation of `sqrt : Double -> Optional<Double>`, that returns an empty Optional
if the input is negative.
If we `map` a function with signature `A -> M B`, we get a function `M A -> M (M B)`.
Now, we need a way to collapse this double-wrapped result into an `M B`.
This operation is called `join`.
Applicative functors that also have `join` are called "monads".
The `map`-and-then-`join` pattern is so common, it has it's own name: `bind`.
For `Optional`, `bind` is implemented by `Optional.flatMap`.

There are formal rules for how these operations need to interact, which you can find elsewhere.
As a rule of thumb, if one only uses these operations, and the types of the results agree,
the values of the results generally agree as well.

If you want to write your own monad, then, simply define `pure`, `apply`, and `join`.
Then, use the monad method script to generate all additional methods and overloads.

## Composition

There's no general way to combine two arbitrary monads `M` and `N` to get a monad,
but we can break the problem down.
First, there is a way to combine `M` and `N` as applicatives, which is done in the code.
The `pure` operations compose directly, while the composed `apply` can be derived by "type tetris" -
choosing operations above to make the type signatures "fit".

To define a composed `join`, we need to define a function `M (N (M (N A))) -> M (N A)`.
If we could "swap" the two middle layers, we'd have an `M (M (N (N A)))`,
to which we could use `map` and `join` to get the desired result.
This "swapping" is called `distribute`, taking inspiration from arithmetic:
A product over a sum "distributes" to a sum of products.
`distribute` cannot be defined for two arbitrary monads,
but it's often not too hard to write `distribute` for two particular monads.
For example, `Expiring` and `ErrorCatching` have a straightforward `distribute` operation.

If you want to compose two monads, copy the definition of `pure`, `apply`, and `join` from an existing composed monad,
like `DynamicsMonad`, and replace the monads you're composing.
Then, write `distribute` for the two monads you're composing to complete the definition of `join`.
Finally, use the monad method script to generate all additional methods and overloads.
