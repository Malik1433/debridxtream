/* global window */
// Stable Picsum-seeded posters so layouts don't flicker on reload.
const P = (seed, w = 400, h = 600) => `https://picsum.photos/seed/${seed}/${w}/${h}`;
const B = (seed, w = 1600, h = 900) => `https://picsum.photos/seed/${seed}/${w}/${h}`;

const HERO = {
  title: "Crimson Horizon",
  tagline: "A Cinematic Journey Through Time",
  synopsis:
    "When a renowned cartographer discovers a map that rewrites history, she is hunted across three continents by a society sworn to keep the past buried. A stylish, breathless thriller from the director of Glasswater.",
  meta: ["2024", "2h 14m", "4K · Dolby Atmos", "PG-13"],
  rating: 8.7,
  match: 96,
  genres: ["Thriller", "Mystery", "Drama"],
  backdrop: B("crimson-horizon-hero", 2400, 1200),
  logo: null,
};

const TRENDING = [
  { id: "t1", title: "Neon Genesis", year: 2024, rating: 8.9, src: P("neon-gen"), badge: "4K" },
  { id: "t2", title: "The Silent Echo", year: 2023, rating: 7.5, src: P("silent-echo") },
  { id: "t3", title: "Midnight Drive", year: 2024, rating: 8.1, src: P("midnight-drv"), badge: "HDR" },
  { id: "t4", title: "Outer Realms", year: 2024, rating: 9.2, src: P("outer-realms"), badge: "4K" },
  { id: "t5", title: "Yesterday's Dreams", year: 2023, rating: 8.4, src: P("yesterday-d") },
  { id: "t6", title: "Glass Tower", year: 2024, rating: 7.9, src: P("glasstower") },
  { id: "t7", title: "After the Storm", year: 2023, rating: 8.0, src: P("after-storm") },
];
const NEW_ARRIVALS = [
  { id: "n1", title: "Lantern Bay", year: 2025, rating: 8.3, src: P("lantern-bay"), badge: "NEW" },
  { id: "n2", title: "Saltwater Kings", year: 2025, rating: 7.8, src: P("saltwater"), badge: "NEW" },
  { id: "n3", title: "The Cartographer", year: 2024, rating: 9.0, src: P("cartograph"), badge: "4K" },
  { id: "n4", title: "Iron Sparrow", year: 2024, rating: 7.4, src: P("iron-sparrow") },
  { id: "n5", title: "Velvet Hours", year: 2024, rating: 8.2, src: P("velvet-hours") },
  { id: "n6", title: "Northbound", year: 2025, rating: 7.6, src: P("northbound"), badge: "NEW" },
  { id: "n7", title: "Paper Moons", year: 2024, rating: 8.0, src: P("paper-moons") },
];
const CONTINUE = [
  { id: "c1", title: "Foundation", subtitle: "S2 · E6 · Halo", progress: 62, src: B("foundation", 600, 340) },
  { id: "c2", title: "Wednesday", subtitle: "S1 · E4", progress: 38, src: B("wednesday", 600, 340) },
  { id: "c3", title: "Dune: Part Two", subtitle: "1h 48m left", progress: 38, src: B("dune2", 600, 340) },
  { id: "c4", title: "True Detective", subtitle: "S4 · E2", progress: 78, src: B("truedet", 600, 340) },
  { id: "c5", title: "The Bear", subtitle: "S3 · E1", progress: 12, src: B("thebear", 600, 340) },
];
const TOP10 = [
  { id: "tx1", title: "The Last Lantern", src: P("toplantern") },
  { id: "tx2", title: "Echoes of Glass", src: P("topglass") },
  { id: "tx3", title: "Crimson Horizon", src: P("topcrimson") },
  { id: "tx4", title: "Northern Crown", src: P("topnorthcrown") },
  { id: "tx5", title: "Cinder & Stone", src: P("topcinder") },
  { id: "tx6", title: "Pale Hourglass", src: P("toppale") },
  { id: "tx7", title: "Wolfsbane", src: P("topwolfs") },
  { id: "tx8", title: "Ember Tide", src: P("topember") },
  { id: "tx9", title: "Quiet Victory", src: P("topquiet") },
  { id: "tx10", title: "Midnight Heir", src: P("topmidnight") },
];

const CHANNELS = [
  { id: "ch1", name: "BBC One HD", num: 101, country: "GB", now: "Crimewatch UK", next: "BBC News at Ten", progress: 64, logoColor: "#C8102E" },
  { id: "ch2", name: "Sky Atlantic", num: 108, country: "GB", now: "House of the Dragon", next: "True Detective", progress: 28, logoColor: "#0072CE" },
  { id: "ch3", name: "ESPN", num: 220, country: "US", now: "NBA: Lakers vs Celtics", next: "SportsCenter", progress: 45, logoColor: "#D70032" },
  { id: "ch4", name: "HBO HD", num: 305, country: "US", now: "The Last of Us", next: "Succession", progress: 80, logoColor: "#5C2D91" },
  { id: "ch5", name: "Discovery+", num: 401, country: "US", now: "Planet Earth III", next: "Mythbusters", progress: 12, logoColor: "#0093D0" },
  { id: "ch6", name: "Netflix Live", num: 502, country: "US", now: "Stranger Things Live", next: "Bridgerton Aftershow", progress: 55, logoColor: "#E50914" },
  { id: "ch7", name: "Canal+ Cinéma", num: 612, country: "FR", now: "Anatomie d'une chute", next: "Cinéma Soir", progress: 22, logoColor: "#000" },
  { id: "ch8", name: "Sky Sports F1", num: 706, country: "GB", now: "Monaco GP — Practice 2", next: "F1 Daily", progress: 71, logoColor: "#E10600" },
];

const EPG_PROGRAMS = [
  { time: "20:00", title: "Crimewatch UK", live: true, progress: 64 },
  { time: "21:00", title: "BBC News at Ten" },
  { time: "21:30", title: "Question Time" },
  { time: "22:30", title: "Newsnight" },
  { time: "23:15", title: "Match of the Day Late" },
  { time: "00:30", title: "Weather for the Week Ahead" },
];

const SERIES = [
  { id: "s1", title: "Foundation", year: 2024, rating: 8.4, src: P("ser-foundation") },
  { id: "s2", title: "House of the Dragon", year: 2024, rating: 8.6, src: P("ser-hotd"), badge: "4K" },
  { id: "s3", title: "Wednesday", year: 2023, rating: 8.1, src: P("ser-wednesday") },
  { id: "s4", title: "True Detective", year: 2024, rating: 9.0, src: P("ser-truedet"), badge: "HDR" },
  { id: "s5", title: "The Bear", year: 2024, rating: 8.7, src: P("ser-bear") },
  { id: "s6", title: "Slow Horses", year: 2024, rating: 8.3, src: P("ser-horses") },
  { id: "s7", title: "Severance", year: 2024, rating: 9.1, src: P("ser-severance"), badge: "4K" },
];

const EPISODES = [
  { num: 1, title: "Halo", duration: "58m", src: B("ep1", 480, 270), synopsis: "Foundation faces its greatest crisis yet as the Empire's grip tightens." },
  { num: 2, title: "King and Commoner", duration: "62m", src: B("ep2", 480, 270), synopsis: "Bel Riose returns from exile with a singular mission." },
  { num: 3, title: "Mournfully Trans Brunette", duration: "55m", src: B("ep3", 480, 270), synopsis: "Hari Seldon confronts the Mule's first move on Trantor." },
  { num: 4, title: "Where the Stars Sing", duration: "60m", src: B("ep4", 480, 270), synopsis: "Salvor reaches the second Foundation through the void." },
  { num: 5, title: "The Sighted and the Seen", duration: "58m", src: B("ep5", 480, 270), synopsis: "Demerzel's loyalty is tested in ways Cleon never anticipated." },
];

window.DATA = { HERO, TRENDING, NEW_ARRIVALS, CONTINUE, TOP10, CHANNELS, EPG_PROGRAMS, SERIES, EPISODES, P, B };
