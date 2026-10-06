# __PKG_NAME__ — a Noxs package.


def main() -> int:
    import sys

    for arg in sys.argv[1:]:
        print(arg)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
