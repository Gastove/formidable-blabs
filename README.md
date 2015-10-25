# formidable-blabs

Hi! I'm Blabs, a Slack bot originally written for the fine people at Formidable
Labs. You could run me, too! I have minimal external dependencies, and a lot of
my behavior can be configured from a flat file. I'm also pretty easy to extend,
if you're in a forking kind of mood. The one thing I'm _not_ is a library.

## Dependencies

Very few of 'em! You need: a recent installation of
[Leiningen](http://leiningen.org/); sqlite3. _Boom_. 

## Setup and Run

Blabs is pretty easy to configure. Do like so:

1. Setup a Bot Integration in your Slack account. Slack has a really sharp UI
   for this. Use their UI.
2. Clone this repo. `cd` in to `formidable_blabs/resources`.
3. Make a copy of the file `local.edn.template`; call it `local.edn`.
4. Replace the block `<REPLACE ME>` with your Slack token.
5. Now `cd` back to the project root and run:

```bash
# One-time setup
$ lein init-db

# Start it up!
# Set env to dev if you want hella verbose logging ;-P
$ NOMAD_ENV=prod lein trampoline run
```

Blabs should now be responding in your Slacks!

## Documentation

Blabs' source is pretty well documented. You can read it directly, or you can
checkout the online version [here](http://blog.gastove.com/formidable-blabs).

## Bugs

Probably! Issues are tracked [in the usual github manner](https://github.com/Gastove/formidable-blabs/issues).

## License

Copyright © 2015 Ross Donaldson

MIT License; see [the LICENSE](LICENSE) for details.
