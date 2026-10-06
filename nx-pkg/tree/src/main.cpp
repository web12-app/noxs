// tree — the noxs-pkg directory tree utility (Noxs package example, C++).
//
// Prints an indented, colorless directory tree for a given path so output
// stays stable over every terminal and locale.
#include <algorithm>
#include <cstring>
#include <dirent.h>
#include <iostream>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {

struct Options {
    bool all = false;
    int max_depth = 3;
};

void list_directory(const std::string &path, const std::string &prefix,
                    int depth, const Options &options) {
    if (depth > options.max_depth) {
        return;
    }
    DIR *dir = opendir(path.c_str());
    if (dir == nullptr) {
        return;
    }
    std::vector<std::string> names;
    while (const dirent *entry = readdir(dir)) {
        const std::string name = entry->d_name;
        if (name == "." || name == "..") {
            continue;
        }
        if (!options.all && !name.empty() && name[0] == '.') {
            continue;
        }
        names.push_back(name);
    }
    closedir(dir);
    std::sort(names.begin(), names.end());

    for (size_t index = 0; index < names.size(); ++index) {
        const bool last = index + 1 == names.size();
        const std::string full = path + "/" + names[index];
        struct stat info {};
        const bool is_dir = stat(full.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
        std::cout << prefix << (last ? "`-- " : "|-- ") << names[index]
                  << (is_dir ? "/" : "") << "\n";
        if (is_dir) {
            list_directory(full, prefix + (last ? "    " : "|   "), depth + 1, options);
        }
    }
}

}  // namespace

int main(int argc, char **argv) {
    Options options;
    std::string target = ".";
    for (int index = 1; index < argc; ++index) {
        const std::string argument = argv[index];
        if (argument == "-a" || argument == "--all") {
            options.all = true;
        } else if (argument == "-L" && index + 1 < argc) {
            options.max_depth = std::atoi(argv[++index]);
        } else if (argument == "-h" || argument == "--help") {
            std::cout << "tree [-a] [-L depth] [path] — noxs-pkg tree utility\n";
            return 0;
        } else {
            target = argument;
        }
    }
    std::cout << target << "\n";
    list_directory(target, "", 1, options);
    return 0;
}
