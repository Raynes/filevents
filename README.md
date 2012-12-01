# filevents

A simple Clojure library for doing file system monitoring.

## Usage

It's pretty simple. There are individual `watch-file` and `watch-directory`
functions, but the one you should be most interested in is `watch`. It can watch
both files and directories and any number of them at once.

```clojure
(watch (fn [kind file]
         (println kind)
         (println file))
       "foo" "bar/")
```

The above example sets up an event on the file `foo` and all files
(recursively) under the directory `bar/`.

It's pretty simple:

* If a file gets modified (in this case `foo` or any file under `bar/`), calls
  the function with the arguments `:modified` and the file itself.
* If a file gets deleted, calls the function with the arguments `:deleted` and
  the file itself.
* If a file is created under a watched directory, calls the function with
  `:created` and the file itself.
  
The library works by polling. All intervals are dynamic vars that can be set
before a watch function is called. By default, the time between checks for
modification and deletion of a file is 300ms because these checks are very
fast. The time between check for newly created files in watched directories is 1
second because this can be slow for large directories because it must populate a
set of files on each iteration and check it against the old set. For large
directories, you might need to set the number higher.


## Pronunciation

Think of filaments.

## License

Copyright © 2012 Anthony Grimes

Distributed under the Eclipse Public License, the same as Clojure.
