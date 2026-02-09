/**
 * Channel and show data for the IPTV app
 * Contains real channel information and show details
 */

export interface Country {
  id: string;
  name: string;
  flag: string;
  channels: number;
}

export interface Show {
  id: string;
  name: string;
  channel: string;
  logo: string;
  views: string;
  quality: string[];
  isPremium?: boolean;
  favorite?: boolean;
  description?: string;
  currentlyPlaying?: string;
  progress?: number;
  thumbnail?: string;
}

export const countries: Country[] = [
  {
    id: 'italy',
    name: 'Italy',
    flag: '🇮🇹',
    channels: 52
  },
  {
    id: 'ukraine',
    name: 'Ukraine',
    flag: '🇺🇦',
    channels: 38
  },
  {
    id: 'brazil',
    name: 'Brazil',
    flag: '🇧🇷',
    channels: 64
  },
  {
    id: 'germany',
    name: 'Germany',
    flag: '🇩🇪',
    channels: 45
  },
  {
    id: 'usa',
    name: 'United States',
    flag: '🇺🇸',
    channels: 147
  },
  {
    id: 'france',
    name: 'France',
    flag: '🇫🇷',
    channels: 41
  },
  {
    id: 'portugal',
    name: 'Portugal',
    flag: '🇵🇹',
    channels: 33
  },
  {
    id: 'south-africa',
    name: 'South Africa',
    flag: '🇿🇦',
    channels: 29
  },
  {
    id: 'china',
    name: 'China',
    flag: '🇨🇳',
    channels: 156
  }
];

export const shows: Show[] = [
  {
    id: '1',
    name: 'Nat Geo Wild HD',
    channel: 'National Geographic',
    logo: 'NAT GEO\nWILD HD',
    views: '+8.2M',
    quality: ['HD', 'EPG'],
    description: 'Explore the wild world with stunning wildlife documentaries',
    currentlyPlaying: 'Animals and Nature',
    progress: 65,
    thumbnail: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=800&h=600&fit=crop'
  },
  {
    id: '2',
    name: 'Disney Channel',
    channel: 'The Walt Disney Company',
    logo: 'DISNEY\nCHANNEL',
    views: '850K',
    quality: ['4K', 'EPG', '$'],
    favorite: true,
    description: 'Premium entertainment for the whole family'
  },
  {
    id: '3',
    name: 'HBO Family',
    channel: 'HBO',
    logo: 'HBO',
    views: '1.7M',
    quality: ['HD', 'EPG'],
    description: 'Premium movies and series for family entertainment'
  },
  {
    id: '4',
    name: 'Netflix Original',
    channel: 'Netflix',
    logo: 'NETFLIX',
    views: '12.5M',
    quality: ['4K', 'HD', 'EPG'],
    favorite: true,
    description: 'Binge-worthy series and blockbuster movies'
  },
  {
    id: '5',
    name: 'Discovery Channel',
    channel: 'Discovery',
    logo: 'DISCOVERY',
    views: '3.2M',
    quality: ['HD', 'EPG'],
    description: 'Fascinating documentaries and real-life adventure'
  },
  {
    id: '6',
    name: 'BBC News',
    channel: 'BBC',
    logo: 'BBC',
    views: '5.4M',
    quality: ['HD'],
    description: 'Latest news from around the world'
  },
  {
    id: '7',
    name: 'ESPN Sports',
    channel: 'ESPN',
    logo: 'ESPN',
    views: '7.8M',
    quality: ['4K', 'EPG'],
    favorite: true,
    description: 'Live sports events and analysis'
  },
  {
    id: '8',
    name: 'Fashion TV HD',
    channel: 'Fashion TV',
    logo: 'FTV',
    views: '2.1M',
    quality: ['HD', 'EPG'],
    description: 'Fashion shows and lifestyle content'
  }
];
