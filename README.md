# formidable-blabs

I'm a slack bot! At the moment, I'm not a super good one. Stay tuned.

## Installation

The only thing you need is a recent installation of [Leiningen](http://leiningen.org/).

## Usage

You'll need a Slack Bot Integration token for the Slack team blabs is meant to
join.

```bash
# First time project setup
git clone https://github.com/Gastove/formidable-blabs && \
cd formidable-blabs && \
echo "{:slack-api-token <TOKEN>} > resources/local.edn"

# Start it up!
# Set env to dev if you want hella verbose logging ;-P
NOMAD_ENV=prod lein trampoline run
```

### Bugs

Probably!

## License

Copyright © 2015 Ross Donaldson

MIT License; see [the LICENSE](LICENSE) for details.
