rootProject.name = "jonoffcpu"

include("jonoffcpu-agent")
project(":jonoffcpu-agent").projectDir = file("jonoffcpu-agent")

include("jonoffcpu-correlator")
project(":jonoffcpu-correlator").projectDir = file("jonoffcpu-correlator")

include("jonoffcpu-jfr-converter")
project(":jonoffcpu-jfr-converter").projectDir = file("jonoffcpu-jfr-converter")
