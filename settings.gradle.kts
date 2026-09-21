rootProject.name = "jonoffcpu"

include("jonoffcpu-agent")
project(":jonoffcpu-agent").projectDir = file("jonoffcpu-agent")

include("jonoffcpu-correlator")
project(":jonoffcpu-correlator").projectDir = file("jonoffcpu-correlator")
